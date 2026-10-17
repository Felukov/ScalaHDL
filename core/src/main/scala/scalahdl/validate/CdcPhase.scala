package scalahdl.validate

import scalahdl.core.*
import scalahdl.clock.ClockDomain
import scalahdl.diag.{Code, Diagnostic, RelatedLoc, Severity}
import scala.collection.mutable

/** R05 — Reject every unmarked read from a foreign clock domain.
  *
  * *Ports* are neutral in both directions — they carry `noClock` by
  * construction (R13.1), which is what makes a module's boundary free and puts
  * the crossing question on the parent's side of it. A `Wire` is **not**
  * neutral: it records the domain it was declared in (R4), which is what stops
  * a crossing being laundered through a combinational intermediate, and R4.1
  * lets it name that domain outright.
  *
  * A read becomes a CDC concern when it reaches a clocked destination or a
  * domain-owned memory port. */
final class CdcPhase extends Validator.Phase:
  def run(ctx: Validator.Ctx): List[Diagnostic] =
    val errors = mutable.ListBuffer.empty[Diagnostic]

    ctx.module.drivers.foreach { driver =>
      driver.stmts.foreach(walkStmt(_, driver.domain, ctx, errors))
    }

    ctx.module.memories.foreach { memory =>
      // R05.5 — distinct by *clock*. Two records naming one clock are one
      // domain, and counting them as two blessed the write-data check away.
      val domains = (memory.reads.map(_.domain) ++ memory.writes.map(_.domain))
        .filterNot(_.isUnclocked).distinctBy(_.clock)
      memory.reads.foreach { port =>
        checkExpr(port.address, port.domain, ctx, errors, blessed = false)
      }
      memory.writes.foreach { port =>
        checkExpr(port.address, port.domain, ctx, errors, blessed = false)
        checkExpr(port.data, port.domain, ctx, errors, blessed = domains.size > 1)
        port.enable.foreach(checkExpr(_, port.domain, ctx, errors, blessed = false))
      }
    }
    errors.toList

  private def walkStmt(stmt: Stmt, destination: ClockDomain, ctx: Validator.Ctx,
                       errors: mutable.ListBuffer[Diagnostic]): Unit = stmt match
    case Stmt.Assign(target, expr, _, loc) =>
      checkExpr(expr, destination, ctx, errors, blessed = false, useLoc = loc)
      checkLhsIndices(target, destination, ctx, errors, loc)
    case Stmt.When(cond, thenStmts, elseStmts, loc) =>
      checkExpr(cond, destination, ctx, errors, blessed = false, useLoc = loc)
      thenStmts.foreach(walkStmt(_, destination, ctx, errors))
      elseStmts.foreach(walkStmt(_, destination, ctx, errors))
    case Stmt.Switch(skind, selector, cases, defaults, loc) =>
      // R05/R22 — a selector read across a domain is a crossing like any
      // other; case values are constants and cross nothing.
      checkExpr(selector, destination, ctx, errors, blessed = false, useLoc = loc)
      cases.foreach(_.stmts.foreach(walkStmt(_, destination, ctx, errors)))
      defaults.foreach(_.foreach(walkStmt(_, destination, ctx, errors)))
    case Stmt.MemWrite(_, addr, data, loc) =>
      checkExpr(addr, destination, ctx, errors, blessed = false, useLoc = loc)
      checkExpr(data, destination, ctx, errors, blessed = false, useLoc = loc)
    case Stmt.Check(_, _, _, _) =>
      // R27/R05 — **a check crosses nothing, because it captures nothing.**
      // R05 exists to stop a value being clocked into a domain that cannot
      // sample it safely; a check clocks nothing in — its only effect is on the
      // simulator's console — so the hazard it guards against has no way to
      // arise. Ruling one out would also make the construct unusable where it
      // is most wanted: an invariant spanning both sides of a crossing ("this
      // FIFO never overflows") is precisely the claim worth stating, and the
      // synchronisers R05 would demand are declarations, which a `simOnly` is
      // forbidden to make. Unreachable as things stand — this phase walks
      // driver blocks and a check is not in one — and correct the day it is.

  private def checkLhsIndices(lhs: Lhs, destination: ClockDomain, ctx: Validator.Ctx,
                              errors: mutable.ListBuffer[Diagnostic], loc: Loc): Unit = lhs match
    case Lhs.Sig(_) => ()
    case Lhs.Field(base, _) => checkLhsIndices(base, destination, ctx, errors, loc)
    case Lhs.Index(base, idx) =>
      checkLhsIndices(base, destination, ctx, errors, loc)
      checkExpr(idx, destination, ctx, errors, blessed = false, useLoc = loc)
    // R6.7 — a window's bounds are constants and cross nothing; the base can
    // still carry a runtime Vec index that does.
    case Lhs.Slice(base, _, _) => checkLhsIndices(base, destination, ctx, errors, loc)
    case Lhs.VarSlice(base, lo, _) =>
      checkExpr(lo, destination, ctx, errors, blessed = false)
      checkLhsIndices(base, destination, ctx, errors, loc)

  private def checkExpr(expr: Expr, destination: ClockDomain, ctx: Validator.Ctx,
                        errors: mutable.ListBuffer[Diagnostic], blessed: Boolean,
                        useLoc: Loc = Loc.NoLoc): Unit = expr match
    case Expr.Ref(id) =>
      val source = ctx.signal(id)
      // R5.6 — foreign is the module's question, not the domain's alone: a
      // different clock the module has declared one source with is not one.
      if !blessed && ctx.module.foreign(source.domain, destination) then
        errors += Diagnostic(
          severity = Severity.Error,
          code = Code.CDC_UNSAFE_CROSSING,
          summary = s"`${source.name}` is read in clock domain `${destination.clkName}` but belongs to `${source.domain.clkName}`",
          primaryLoc = if useLoc != Loc.NoLoc then useLoc else source.loc,
          related = List(RelatedLoc(source.loc,
            s"`${source.name}` declared in source domain `${source.domain.clkName}`")),
          fix = Some(s"cross `${source.name}` through SyncChain, PulseSync, AsyncFifo, or unsafeCrossDomain with a justification"))
    case Expr.TypeCast(_, inner) =>
      checkExpr(inner, destination, ctx, errors, blessed, useLoc)
    case Expr.Cdc(primitive, inner, markerLoc) =>
      validatePrimitive(primitive, inner, destination, ctx, errors,
        if markerLoc != Loc.NoLoc then markerLoc else useLoc)
      checkExpr(inner, destination, ctx, errors, blessed = true, useLoc)
    case Expr.UnOp(_, arg) => checkExpr(arg, destination, ctx, errors, blessed, useLoc)
    case Expr.BinOp(_, lhs, rhs) =>
      checkExpr(lhs, destination, ctx, errors, blessed, useLoc)
      checkExpr(rhs, destination, ctx, errors, blessed, useLoc)
    case Expr.Mux(sel, whenTrue, whenFalse) =>
      checkExpr(sel, destination, ctx, errors, blessed, useLoc)
      checkExpr(whenTrue, destination, ctx, errors, blessed, useLoc)
      checkExpr(whenFalse, destination, ctx, errors, blessed, useLoc)
    case Expr.Slice(base, _, _) => checkExpr(base, destination, ctx, errors, blessed, useLoc)
    case Expr.VarSlice(base, lo, _) =>
      checkExpr(base, destination, ctx, errors, blessed, useLoc)
      checkExpr(lo, destination, ctx, errors, blessed, useLoc)
    case Expr.Index(base, idx) =>
      checkExpr(base, destination, ctx, errors, blessed, useLoc)
      checkExpr(idx, destination, ctx, errors, blessed, useLoc)
    case Expr.Field(base, _) => checkExpr(base, destination, ctx, errors, blessed, useLoc)
    case Expr.Cat(parts) => parts.foreach(checkExpr(_, destination, ctx, errors, blessed, useLoc))
    case Expr.Repeat(_, inner) => checkExpr(inner, destination, ctx, errors, blessed, useLoc)
    case Expr.Resize(_, inner) => checkExpr(inner, destination, ctx, errors, blessed, useLoc)
    case Expr.Lit(_, _, _) => ()

  private def validatePrimitive(primitive: CdcPrimitive, inner: Expr,
                                destination: ClockDomain, ctx: Validator.Ctx,
                                errors: mutable.ListBuffer[Diagnostic], loc: Loc): Unit =
    val sourceType = WidthPhase.typeOf(inner, ctx)

    // R05.7 — an input declared asynchronous has no source clock, so the two
    // primitives that assume one cannot be what carries it across: a pulse one
    // clock wide, and a FIFO written on a clock. A synchroniser assumes
    // neither, which is why it is the one that may.
    def asynchronousSource: Option[Signal] =
      def find(expr: Expr): Option[Signal] = expr match
        case Expr.Ref(id)             => Some(ctx.signal(id)).filter(_.domain.isAsynchronous)
        case Expr.TypeCast(_, nested) => find(nested)
        case Expr.Slice(base, _, _)   => find(base)
        case Expr.Field(base, _)      => find(base)
        case _                        => None
      find(inner)

    def refuseAsynchronous(name: String, assumes: String): Unit =
      asynchronousSource.foreach { source =>
        errors += Diagnostic(Severity.Error, Code.CDC_UNSAFE_CROSSING,
          s"`${source.name}` is an asynchronous input and $name assumes $assumes; " +
            "SyncChain is the one primitive that may read one",
          loc,
          fix = Some(s"read `${source.name}` through SyncChain, or unsafeCrossDomain with a justification"))
      }

    primitive match
      case CdcPrimitive.SyncChain(stages) =>
        val legal = sourceType == Type.Bool || sourceType == Type.Bits(1) ||
          sourceType.isInstanceOf[Type.GrayCode]
        if stages < 2 || !legal then
          errors += Diagnostic(Severity.Error, Code.CDC_UNSAFE_CROSSING,
            s"SyncChain requires at least two stages and Bool/Bits(1)/GrayCode input; got stages=$stages, type=$sourceType",
            loc,
            fix = Some("use AsyncFifo for ordinary multi-bit data, or encode a pointer as GrayCode"))
      case CdcPrimitive.PulseSync =>
        refuseAsynchronous("PulseSync", "a pulse one clock wide in a source domain")
        if sourceType != Type.Bool && sourceType != Type.Bits(1) then
          errors += Diagnostic(Severity.Error, Code.CDC_UNSAFE_CROSSING,
            s"PulseSync requires a single-bit event, got $sourceType", loc)
      case CdcPrimitive.AsyncFifo =>
        refuseAsynchronous("AsyncFifo", "a source clock to write on")
      case CdcPrimitive.Unsafe(justification) if justification.trim.isEmpty =>
        errors += Diagnostic(Severity.Error, Code.CDC_UNSAFE_CROSSING,
          "unsafeCrossDomain requires a non-empty written justification", loc)
      case _ => ()

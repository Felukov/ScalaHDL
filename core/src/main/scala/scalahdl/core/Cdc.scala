package scalahdl.core

import scalahdl.clock.ClockDomain

/** R05 — The blessed mechanism attached to an explicit CDC expression. */
enum CdcPrimitive:
  case SyncChain(stages: Int)
  case PulseSync
  case AsyncFifo
  case DualDomainMemory(memoryName: String)
  case Unsafe(justification: String)

  def reportName: String = this match
    case SyncChain(stages) => s"SyncChain($stages)"
    case PulseSync         => "PulseSync"
    case AsyncFifo         => "AsyncFifo"
    case DualDomainMemory(memoryName) => s"DualDomainMemory($memoryName)"
    case Unsafe(_)         => "unsafeCrossDomain"

  def isSafe: Boolean = this match
    case Unsafe(_) => false
    case _         => true

/** R05.1 — One structurally identified crossing, collected during validation
  * and rendered deterministically beside emitted Verilog. */
final case class CdcCrossing(
  signalId: SignalId,
  sourceDomain: ClockDomain,
  destDomain: ClockDomain,
  primitive: CdcPrimitive,
  loc: Loc
)

object CdcReport:
  /** Render all explicit crossings in source/declaration order. */
  def render(module: Module): String =
    val crossings = collect(module)
    val safe = crossings.filter(_.primitive.isSafe)
    val unsafe = crossings.filterNot(_.primitive.isSafe)
    val names = module.signals.iterator.map(s => s.id -> s.name).toMap

    def domainName(domain: ClockDomain): String =
      if domain.isUnclocked then "unclocked" else domain.clkName

    def line(crossing: CdcCrossing): String =
      val signal = names.getOrElse(crossing.signalId, s"sig${crossing.signalId.value}")
      val reason = crossing.primitive match
        case CdcPrimitive.Unsafe(justification) => s"; justification: $justification"
        case _                                  => ""
      s"- $signal: ${domainName(crossing.sourceDomain)} -> ${domainName(crossing.destDomain)} via ${crossing.primitive.reportName}$reason"

    def section(title: String, entries: List[CdcCrossing]): String =
      val body = if entries.isEmpty then "(none)" else entries.map(line).mkString("\n")
      s"$title\n$body\n"

    // R5.6 — the families this module declared, so a reader of an empty
    // Unsafe list can see *why* a read between two clocks is not in it, and a
    // board's timing constraints have something to be checked against.
    // Present only when there is one: a module with one clock has nothing to
    // say here, and its report does not change for a rule it does not use.
    val related =
      if module.relatedClocks.isEmpty then ""
      else
        val families = module.relatedClocks.map { family =>
          "- " + family.map(id => names.getOrElse(id, s"sig${id.value}")).mkString(", ")
        }
        s"Related clocks\n${families.mkString("\n")}\n\n"

    s"CDC report: ${module.name}\n\n" + related +
      section("Safe", safe) + "\n" + section("Unsafe", unsafe)

  /** Explicit markers are retained in the immutable expression tree. This
    * collector deliberately reports only genuine foreign-domain markers. */
  def collect(module: Module): List[CdcCrossing] =
    val signals = module.signals.iterator.map(s => s.id -> s).toMap
    val out = scala.collection.mutable.ListBuffer.empty[CdcCrossing]

    def refs(expr: Expr): List[SignalId] = expr match
      case Expr.Ref(id)                   => List(id)
      case Expr.Lit(_, _, _)              => Nil
      case Expr.UnOp(_, arg)              => refs(arg)
      case Expr.BinOp(_, lhs, rhs)        => refs(lhs) ++ refs(rhs)
      case Expr.Mux(sel, whenTrue, whenFalse) => refs(sel) ++ refs(whenTrue) ++ refs(whenFalse)
      case Expr.Slice(base, _, _)          => refs(base)
      case Expr.VarSlice(base, lo, _)      => refs(base) ++ refs(lo)
      case Expr.Index(base, idx)           => refs(base) ++ refs(idx)
      case Expr.Field(base, _)             => refs(base)
      case Expr.Cat(parts)                 => parts.flatMap(refs)
      case Expr.Repeat(_, inner)           => refs(inner)
      case Expr.Resize(_, inner)           => refs(inner)
      case Expr.TypeCast(_, inner)         => refs(inner)
      case Expr.Cdc(_, inner, _)           => refs(inner)

    def walkExpr(expr: Expr, dest: ClockDomain): Unit = expr match
      case Expr.Cdc(primitive, inner, loc) =>
        refs(inner).distinct.foreach { id =>
          signals.get(id).foreach { signal =>
            if module.foreign(signal.domain, dest) then
              out += CdcCrossing(id, signal.domain, dest, primitive, loc)
          }
        }
        walkExpr(inner, dest)
      case Expr.UnOp(_, arg)                    => walkExpr(arg, dest)
      case Expr.BinOp(_, lhs, rhs)              => walkExpr(lhs, dest); walkExpr(rhs, dest)
      case Expr.Mux(sel, whenTrue, whenFalse)   => walkExpr(sel, dest); walkExpr(whenTrue, dest); walkExpr(whenFalse, dest)
      case Expr.Slice(base, _, _)               => walkExpr(base, dest)
      case Expr.VarSlice(base, lo, _)           => walkExpr(base, dest); walkExpr(lo, dest)
      case Expr.Index(base, idx)                => walkExpr(base, dest); walkExpr(idx, dest)
      case Expr.Field(base, _)                  => walkExpr(base, dest)
      case Expr.Cat(parts)                      => parts.foreach(walkExpr(_, dest))
      case Expr.Repeat(_, inner)                => walkExpr(inner, dest)
      case Expr.Resize(_, inner)                => walkExpr(inner, dest)
      case Expr.TypeCast(_, inner)              => walkExpr(inner, dest)
      case _                                    => ()

    def walkStmt(stmt: Stmt, dest: ClockDomain): Unit = stmt match
      case Stmt.Assign(_, expr, _, _)          => walkExpr(expr, dest)
      case Stmt.When(cond, thenStmts, elseStmts, _) =>
        walkExpr(cond, dest)
        thenStmts.foreach(walkStmt(_, dest))
        elseStmts.foreach(walkStmt(_, dest))
      case Stmt.Switch(skind, selector, cases, defaults, _) =>   // R22
        walkExpr(selector, dest)
        cases.foreach(_.stmts.foreach(walkStmt(_, dest)))
        defaults.foreach(_.foreach(walkStmt(_, dest)))
      case Stmt.MemWrite(_, addr, data, _)     => walkExpr(addr, dest); walkExpr(data, dest)
      // R27 — a check reads without capturing, so it contributes no crossing.
      // See `CdcPhase` for the argument.
      case Stmt.Check(_, _, _, _)              => ()

    module.drivers.foreach(db => db.stmts.foreach(walkStmt(_, db.domain)))
    module.memories.foreach { memory =>
      memory.reads.foreach(port => walkExpr(port.address, port.domain))
      memory.writes.foreach { port =>
        walkExpr(port.address, port.domain)
        walkExpr(port.data, port.domain)
        port.enable.foreach(walkExpr(_, port.domain))
      }
      // R05.5 — distinct by *clock*, or a memory with one clock and two reset
      // shapes reports a dual-domain crossing that does not exist.
      val domains = (memory.reads.map(_.domain) ++ memory.writes.map(_.domain))
        .filterNot(_.isUnclocked).distinctBy(_.clock)
      if domains.size > 1 then
        val representative = memory.reads.headOption.map(_.result).orElse(
          memory.writes.iterator.flatMap(port => refs(port.data)).toSeq.headOption)
        representative.foreach { id =>
          domains.zip(domains.drop(1)).foreach { case (source, dest) =>
            out += CdcCrossing(id, source, dest,
              CdcPrimitive.DualDomainMemory(memory.name), memory.loc)
          }
        }
    }
    out.toList

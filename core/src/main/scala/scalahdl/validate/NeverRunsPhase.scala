package scalahdl.validate

import scalahdl.core.*
import scalahdl.diag.{Code, Diagnostic, Severity}

/** R12.1's other half — **a combinational block that could never run**.
  *
  * A procedural combinational block is sensitive to what it reads (IEEE 1800
  * §9.4.2.2). A block that reads nothing has an empty sensitivity list, no
  * event can trigger it, and its target keeps its initial value for the whole
  * simulation: the design says one thing and the Verilog does another, with
  * nothing but a lint warning in between.
  *
  * Almost every such block is a driver of unconditional assignments, and R12.1
  * emits those continuously, where a constant holds from time zero — the
  * failure is unrepresentable rather than detected. What is left is the one
  * shape that cannot be continuous: a write to *part* of a signal (R6.7),
  * whose other bits must hold, from an expression that reads nothing. That is
  * this error.
  */
final class NeverRunsPhase extends Validator.Phase:

    def run(ctx: Validator.Ctx): List[Diagnostic] =
        ctx.module.drivers.filter(db =>
            !db.clocked && ContinuousDriver.assigns(db).isEmpty && reads(db.stmts).isEmpty
        ).map { db =>
            val signal = ctx.module.signals.find(_.id == db.signalId)
            val name = signal.map(_.name).getOrElse("?")
            Diagnostic(
                severity = Severity.Error,
                code = Code.COMBINATIONAL_NEVER_RUNS,
                summary = s"the block driving `$name` reads nothing, so nothing can trigger it",
                primaryLoc = signal.map(_.loc).getOrElse(Loc.NoLoc),
                fix = Some("drive the whole signal rather than a part of it, so it can be a " +
                           "continuous assignment; or make it a register, which needs no event"))
        }

    /** Every signal a statement reads: the right-hand sides, the conditions a
      * `when` and a `switch` branch on, and a dynamic index. */
    private def reads(stmts: List[Stmt]): Set[SignalId] = stmts.flatMap {
        case Stmt.Assign(target, expr, _, _)  => refs(expr) ++ indexRefs(target)
        case Stmt.When(cond, t, e, _)         => refs(cond) ++ reads(t) ++ reads(e)
        case Stmt.Switch(_, selector, cases, default, _) =>
            // The labels count: R26's selection on conditions is a switch on
            // the constant one, and every condition it branches on is a label.
            refs(selector) ++ cases.flatMap(c => c.values.flatMap(refs) ++ reads(c.stmts)) ++
                default.toList.flatMap(reads)
        case Stmt.MemWrite(_, addr, data, _)  => refs(addr) ++ refs(data)
        case _                                => Set.empty[SignalId]
    }.toSet

    private def refs(e: Expr): Set[SignalId] = e match
        case Expr.Ref(id)            => Set(id)
        case Expr.Field(base, _)     => refs(base)
        case Expr.Index(base, idx)   => refs(base) ++ refs(idx)
        case Expr.Slice(base, _, _)  => refs(base)
        case Expr.VarSlice(base, lo, _) => refs(base) ++ refs(lo)   // R34
        case Expr.UnOp(_, a)         => refs(a)
        case Expr.BinOp(_, a, b)     => refs(a) ++ refs(b)
        case Expr.Mux(sel, a, b)     => refs(sel) ++ refs(a) ++ refs(b)
        case Expr.Cat(parts)         => parts.flatMap(refs).toSet
        case Expr.Resize(_, a)       => refs(a)
        case Expr.Repeat(_, a)       => refs(a)
        case Expr.TypeCast(_, a)     => refs(a)
        case Expr.Cdc(_, a, _)       => refs(a)
        case _                       => Set.empty[SignalId]

    private def indexRefs(lhs: Lhs): Set[SignalId] = lhs match
        case Lhs.Index(base, idx)    => indexRefs(base) ++ refs(idx)
        case Lhs.Field(base, _)      => indexRefs(base)
        case Lhs.Slice(base, _, _)   => indexRefs(base)
        case Lhs.VarSlice(base, lo, _) => indexRefs(base) ++ refs(lo)   // R34
        case _                       => Set.empty[SignalId]
end NeverRunsPhase

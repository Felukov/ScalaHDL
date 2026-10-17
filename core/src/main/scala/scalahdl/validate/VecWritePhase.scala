package scalahdl.validate

import scalahdl.core.*
import SignalId.*
import scalahdl.diag.{Code, Diagnostic, Severity, RelatedLoc}
import scala.collection.mutable

/** R14.2 and R6.7 — the two writes that a register can take and a wire cannot.
  *
  * R14.2 — Dynamic-index writes require registers. `vec(i) := x` with a
  * runtime `i` is legal only on a `Reg` Vec, where it lowers to a variable
  * index inside the `always` block (legal Verilog, no generated logic). The
  * same write on a combinational (wire) Vec is an elaboration error, pointing
  * at R14.3 (`decode` + explicit per-element `Mux`) or making it a `Reg`.
  *
  * A constant-index write (`vec(2) := x`) is legal on either kind — Verilog-2001
  * permits a constant index in a continuous assignment, so no decoder is
  * needed.
  *
  * R6.7 — a **part-select** target (`count(7, 0) := d`) is here for the same
  * reason and decided the same way: it drives some of a signal's bits, so the
  * rest have to mean *hold*, and only a register means that. On a wire the
  * unnamed bits are a latch, which is exactly what R11 exists to make loud —
  * and R11 could not see it, because it asks whether a signal was driven and a
  * part-select does drive it. The two remaining checks are bounds, and a
  * refusal to put a window on a `Vec` element, which Verilog-2001 cannot
  * spell.
  *
  * The phase walks every driver block's statements; for an `Assign` whose
  * `Lhs` is a runtime-indexed `Lhs.Index` (the index expression is not a
  * literal), it checks the root signal's kind. Errors batch within the
  * phase. */
final class VecWritePhase extends Validator.Phase:

  def run(ctx: Validator.Ctx): List[Diagnostic] =
    val errs = mutable.ListBuffer.empty[Diagnostic]
    ctx.module.drivers.foreach { db =>
      db.stmts.foreach(walkStmt(_, ctx, errs))
    }
    // R27 — a check has no target, but it *reads*, and an index that is out of
    // range or a bit-select of an anonymous tree is as wrong in a claim as in
    // an assignment. A check that cannot be interpreted is worse than no check.
    ctx.module.checks.foreach { cb =>
      cb.stmts.foreach(walkStmt(_, ctx, errs))
    }
    errs.result()

  private def walkStmt(s: Stmt, ctx: Validator.Ctx,
                       errs: mutable.ListBuffer[Diagnostic]): Unit =
    s match
      case Stmt.Assign(target, expr, _, loc) =>
        checkTarget(target, loc, ctx, errs)
        checkExpr(expr, loc, ctx, errs)
      case Stmt.When(cond, thenStmts, elseStmts, loc) =>
        checkExpr(cond, loc, ctx, errs)
        thenStmts.foreach(walkStmt(_, ctx, errs))
        elseStmts.foreach(walkStmt(_, ctx, errs))
      case Stmt.Switch(skind, selector, cases, defaults, loc) =>   // R22
        checkExpr(selector, loc, ctx, errs)
        cases.foreach(_.stmts.foreach(walkStmt(_, ctx, errs)))
        defaults.foreach(_.foreach(walkStmt(_, ctx, errs)))
      case Stmt.Check(_, cond, _, loc) =>
        // R27 — read-only: there is no target to ask R14.2's question about,
        // and the condition is checked like any other expression.
        checkExpr(cond, loc, ctx, errs)
      // A memory write port is checked with the memory, not as a vector write.
      case Stmt.MemWrite(_, _, _, _) => ()

  /** Check an assignment target: a runtime-indexed `Lhs.Index` on a
    * combinational (wire) Vec is an error. */
  private def checkTarget(lhs: Lhs, assignLoc: Loc, ctx: Validator.Ctx,
                          errs: mutable.ListBuffer[Diagnostic]): Unit =
    lhs match
      case Lhs.Index(base, idx) =>
        // Interpret this index against the type of its immediate base. Runtime
        // scalar bit-selects are not Vec writes and must not trigger R14.2.
        lhsType(base, ctx) match
          case Type.Vec(_, _) => idx match
            case Expr.Lit(_, _, _) => ()
            case _ =>
              val root = ctx.signal(lhs.rootId)
              if !root.isReg then
                errs += Diagnostic(
                  severity = Severity.Error,
                  code = Code.DYNAMIC_WRITE_COMBINATIONAL,
                  summary =
                    s"`${root.name}` is a combinational Vec; a runtime-index " +
                    s"write is a decoder, not a native write",
                  primaryLoc = if assignLoc != Loc.NoLoc then assignLoc else root.loc,
                  related = List(RelatedLoc(root.loc, s"`${root.name}` declared here")),
                  fix = Some(
                    "make it a `Reg` Vec, or use `decode` + explicit per-element " +
                    "`Mux` assignments"))
          case _ => ()
        checkTarget(base, assignLoc, ctx, errs)
      case Lhs.Field(base, _) => checkTarget(base, assignLoc, ctx, errs)
      case Lhs.Sig(_)         => ()
      case Lhs.Slice(base, hi, lo) =>
        checkWindow(lhs, base, hi, lo, assignLoc, ctx, errs)
        checkTarget(base, assignLoc, ctx, errs)
      case Lhs.VarSlice(base, lo, width) =>
        // R34 — a runtime-offset window is a partial write like a constant one,
        // and takes the same register-only rule.
        checkVarWindow(lhs, base, width, assignLoc, ctx, errs)
        checkExpr(lo, assignLoc, ctx, errs)
        checkTarget(base, assignLoc, ctx, errs)

  /** R6.7 — a part-select is an assignment target on a register, in range, and
    * not on an element of a `Vec`. */
  private def checkWindow(whole: Lhs, base: Lhs, hi: Int, lo: Int, assignLoc: Loc,
                          ctx: Validator.Ctx,
                          errs: mutable.ListBuffer[Diagnostic]): Unit =
    val root = ctx.signal(whole.rootId)
    def where = if assignLoc != Loc.NoLoc then assignLoc else root.loc
    def declared = List(RelatedLoc(root.loc, s"`${root.name}` declared here"))

    // A register's unwritten bits hold, which is R11.1 read one bit at a time.
    // A wire's unwritten bits are a latch, which is the whole of what R11
    // exists to make loud — so the rule is the kind of the signal, not the
    // shape of the block around it.
    if !root.isReg then
      errs += Diagnostic(
        severity = Severity.Error,
        code = Code.PARTIAL_WRITE_COMBINATIONAL,
        summary =
          s"`${root.name}` is combinational, and a part-select drives only " +
          s"bits $hi..$lo of it — the rest would be a latch",
        primaryLoc = where,
        related = declared,
        fix = Some(
          "assign the whole signal with `Cat(...)`, or make it a `Reg`, where " +
          "the bits a write does not name hold their value"))

    // Verilog-2001 has no bit- or part-select of a memory element, so a window
    // on a Vec element has no spelling. Bundle fields are flattened to plain
    // names (R7.1) and are fine.
    if containsVecIndex(base, ctx) then
      errs += Diagnostic(
        severity = Severity.Error,
        code = Code.PARTIAL_WRITE_VEC_ELEMENT,
        summary =
          s"`${root.name}` is indexed before the window, and Verilog-2001 has " +
          "no part-select of an array element",
        primaryLoc = where,
        related = declared,
        fix = Some(
          "read the element into a `val`, build the whole word, and write the " +
          "element once"))

    val width = lhsType(base, ctx).bitWidth
    if lo < 0 || hi >= width then
      errs += Diagnostic(
        severity = Severity.Error,
        code = Code.PARTIAL_WRITE_OUT_OF_BOUNDS,
        summary =
          s"the window ($hi, $lo) is outside `${root.name}`'s ${width - 1}..0",
        primaryLoc = where,
        related = declared,
        fix = Some(s"select within 0..${width - 1}, or widen the signal"))

  /** R34 — a runtime-offset window (`word(idx, w)`) drives part of a signal, so
    * the rest must hold: the same reason and the same rule R6.7 gives a
    * constant window. The offset is a runtime value and cannot be
    * bounds-checked here; the width and the base can. */
  private def checkVarWindow(whole: Lhs, base: Lhs, width: Int, assignLoc: Loc,
                             ctx: Validator.Ctx,
                             errs: mutable.ListBuffer[Diagnostic]): Unit =
    val root = ctx.signal(whole.rootId)
    def where = if assignLoc != Loc.NoLoc then assignLoc else root.loc
    def declared = List(RelatedLoc(root.loc, s"`${root.name}` declared here"))

    if !root.isReg then
      errs += Diagnostic(
        severity = Severity.Error,
        code = Code.PARTIAL_WRITE_COMBINATIONAL,
        summary =
          s"`${root.name}` is combinational, and a runtime part-select drives " +
          s"only $width bits of it — the rest would be a latch",
        primaryLoc = where,
        related = declared,
        fix = Some(
          "assign the whole signal with `Cat(...)`, or make it a `Reg`, where " +
          "the bits a write does not name hold their value"))

    if containsVecIndex(base, ctx) then
      errs += Diagnostic(
        severity = Severity.Error,
        code = Code.PARTIAL_WRITE_VEC_ELEMENT,
        summary =
          s"`${root.name}` is indexed before the window, and Verilog-2001 has " +
          "no part-select of an array element",
        primaryLoc = where,
        related = declared,
        fix = Some(
          "read the element into a `val`, build the whole word, and write the " +
          "element once"))

    val baseWidth = lhsType(base, ctx).bitWidth
    if width > baseWidth then
      errs += Diagnostic(
        severity = Severity.Error,
        code = Code.PARTIAL_WRITE_OUT_OF_BOUNDS,
        summary =
          s"a $width-bit window does not fit in `${root.name}`'s $baseWidth bits",
        primaryLoc = where,
        related = declared,
        fix = Some(s"use a width of at most $baseWidth"))

  /** Whether a path reaches its window through a `Vec` element. */
  private def containsVecIndex(lhs: Lhs, ctx: Validator.Ctx): Boolean = lhs match
    case Lhs.Sig(_)              => false
    case Lhs.Field(base, _)      => containsVecIndex(base, ctx)
    case Lhs.Slice(base, _, _)   => containsVecIndex(base, ctx)
    case Lhs.VarSlice(base, _, _) => containsVecIndex(base, ctx)
    case Lhs.Index(base, _)      => lhsType(base, ctx) match
      case Type.Vec(_, _) => true
      case _              => containsVecIndex(base, ctx)

  private def lhsType(lhs: Lhs, ctx: Validator.Ctx): Type = lhs match
    case Lhs.Sig(id) => ctx.signal(id).typeOf
    case Lhs.Field(base, name) => lhsType(base, ctx) match
      case Type.Bundle(fields) =>
        fields.find(_._1 == name).map(_._2).getOrElse(Type.Bool)
      case other => other
    case Lhs.Index(base, _) => lhsType(base, ctx) match
      case Type.Vec(_, elem) => elem
      case _: Type.UInt | _: Type.SInt | _: Type.Bits | _: Type.GrayCode => Type.Bool
      case other => other
    case Lhs.Slice(_, hi, lo) => Type.UInt(hi - lo + 1)
    case Lhs.VarSlice(_, _, width) => Type.UInt(width)

  /** Validate Vec indexing on RHS/conditions before emission. Runtime indexing
    * of a flattened port Vec has no Verilog-2001 representation; constants are
    * bounds-checked at every nested Vec dimension. */
  private def checkExpr(e: Expr, loc: Loc, ctx: Validator.Ctx,
                        errs: mutable.ListBuffer[Diagnostic]): Unit = e match
    case Expr.Ref(_) | Expr.Lit(_, _, _) => ()
    case Expr.UnOp(_, arg) => checkExpr(arg, loc, ctx, errs)
    case Expr.BinOp(_, lhs, rhs) =>
      checkExpr(lhs, loc, ctx, errs); checkExpr(rhs, loc, ctx, errs)
    case Expr.Mux(sel, whenTrue, whenFalse) =>
      checkExpr(sel, loc, ctx, errs)
      checkExpr(whenTrue, loc, ctx, errs)
      checkExpr(whenFalse, loc, ctx, errs)
    case Expr.Slice(base, _, _) => checkExpr(base, loc, ctx, errs)
    case Expr.VarSlice(base, lo, _) =>
      checkExpr(base, loc, ctx, errs); checkExpr(lo, loc, ctx, errs)
    case Expr.Field(base, _) => checkExpr(base, loc, ctx, errs)
    case Expr.Cat(parts) => parts.foreach(checkExpr(_, loc, ctx, errs))
    case Expr.Repeat(_, inner) => checkExpr(inner, loc, ctx, errs)
    case Expr.Resize(_, inner) => checkExpr(inner, loc, ctx, errs)
    case Expr.TypeCast(_, inner) => checkExpr(inner, loc, ctx, errs)
    case Expr.Cdc(_, inner, _) => checkExpr(inner, loc, ctx, errs)
    case Expr.Index(base, idx) =>
      exprType(base, ctx) match
        case Type.Vec(n, _) => idx match
          case Expr.Lit(v, _, _) if v < 0 || v >= n =>
            errs += indexDiagnostic(Code.VEC_INDEX_OUT_OF_BOUNDS,
              s"Vec index $v is outside 0..${n - 1}", base, loc, ctx)
          case _: Expr.Lit => ()
          case _ if rootId(base).exists(id => isPort(id, ctx)) =>
            errs += indexDiagnostic(Code.RUNTIME_INDEX_PORT_VEC,
              "runtime indexing a port Vec is illegal because Verilog-2001 ports are flattened per element",
              base, loc, ctx)
          case _ => ()
        case _ => ()
      checkExpr(base, loc, ctx, errs)
      checkExpr(idx, loc, ctx, errs)

  private def exprType(e: Expr, ctx: Validator.Ctx): Type = e match
    case Expr.Ref(id) => ctx.signal(id).typeOf
    case Expr.Field(base, name) => exprType(base, ctx) match
      case Type.Bundle(fields) => fields.find(_._1 == name).map(_._2).getOrElse(Type.Bool)
      case other => other
    case Expr.Index(base, _) => exprType(base, ctx) match
      case Type.Vec(_, elem) => elem
      case _: Type.UInt | _: Type.SInt | _: Type.Bits | _: Type.GrayCode => Type.Bool
      case other => other
    case Expr.VarSlice(_, _, width) => Type.UInt(width)
    case Expr.TypeCast(to, _) => to
    case Expr.Cdc(_, inner, _) => exprType(inner, ctx)
    case _ => Type.Bool

  private def rootId(e: Expr): Option[SignalId] = e match
    case Expr.Ref(id) => Some(id)
    case Expr.Field(base, _) => rootId(base)
    case Expr.Index(base, _) => rootId(base)
    case Expr.VarSlice(base, _, _) => rootId(base)
    case Expr.TypeCast(_, inner) => rootId(inner)
    case Expr.Cdc(_, inner, _) => rootId(inner)
    case _ => None

  private def isPort(id: SignalId, ctx: Validator.Ctx): Boolean =
    val rootName = ctx.signal(id).name
    ctx.module.ports.exists(_.name == rootName)

  private def indexDiagnostic(code: Code, summary: String, base: Expr, loc: Loc,
                              ctx: Validator.Ctx): Diagnostic =
    val declaration = rootId(base).map(ctx.signal)
    Diagnostic(
      severity = Severity.Error,
      code = code,
      summary = summary,
      primaryLoc = if loc != Loc.NoLoc then loc else declaration.map(_.loc).getOrElse(Loc.NoLoc),
      related = declaration.toList.map(s => RelatedLoc(s.loc, s"`${s.name}` declared here")),
      fix = Some("use an in-range constant index, or copy the port into an internal Vec before runtime indexing"))

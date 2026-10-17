package scalahdl.validate

import scalahdl.core.*
import SignalId.*
import scalahdl.diag.{Code, Diagnostic, Severity, RelatedLoc}
import scala.collection.mutable

/** R06 / R21 — Elaboration-time widths and type admission. No implicit width
  * extension, no implicit signedness conversion. Widths are values, checked
  * here against the IR.
  *
  * The phase walks every driver block's statements; for each `:=` it checks
  * that the expression's inferred width and signedness agree with the target
  * signal's type, and for each operator that its operands are admissible and
  * agree.
  *
  * R19's two locations are *declared* and *used wrongly*, and they differ. The
  * declarations come from the signals the expression references; the use site
  * is the enclosing statement's own `Loc` — the `:=`, the `when`, the `is`, the
  * memory write — which is what `site` threads through every check below. It
  * is the primary location, so the caret lands on the line that got it wrong
  * and the declarations render as `note:`s beneath it. Before this the primary
  * location *was* a declaration — a line the notes already carry, and the same
  * line for every misuse of that signal, so twenty bad comparisons against one
  * port all reported at the port. Where a statement has no location (`NoLoc` —
  * a library helper assembling assignments of its own) the phase falls back to
  * the first declaration, which is the shape every diagnostic here used to
  * have.
  *
  * Result widths per op are not decided here: they come from `ExprType`
  * (R21.6), the single typer shared with the builder's literal resolution and
  * the emitter's `$signed` decisions. This phase's job is admission and
  * agreement.
  *
  *   - `+`, `-`, `&`, `|`, `^` → operand width `w`   (no carry bit; truncation
  *     at the target is native, R6.2)
  *   - `*` → `w1 + w2`, the full product (R21.3)
  *   - `<<`, `>>`, `>>>` → the left operand's width (R21.1)
  *   - `==`, `!=`, `<`, `<=`, `>`, `>=` → `Bool`
  *
  * Type admission follows R21's table rather than one "is it arithmetic"
  * grouping: `Bits` has no arithmetic *but does have bitwise operations*, so
  * `&`/`|`/`^`/`~`, shifts, equality, and reductions are legal on it while
  * `+`, `-`, `*`, and ordering are not. Ordering counts as arithmetic in this
  * sense: a `<` on `Bits` could only mean an accidentally unsigned comparison,
  * which is what R21 exists to prevent. */
final class WidthPhase extends Validator.Phase:
  import WidthPhase.*

  def run(ctx: Validator.Ctx): List[Diagnostic] =
    val errs = mutable.ListBuffer.empty[Diagnostic]
    ctx.module.drivers.foreach { db =>
      db.stmts.foreach(walkStmt(_, ctx, errs))
    }
    // R27 — a check block's statements are checked exactly like a driver's. An
    // assertion drives nothing, but its condition is an ordinary expression and
    // a claim written against the wrong width is a claim about nothing: the
    // whole value of a check is that it is true of the design, so this is the
    // one place it can be established that it is even *asking* about it.
    ctx.module.checks.foreach { cb =>
      cb.stmts.foreach(walkStmt(_, ctx, errs))
    }
    errs.result()

  /** Walk a statement, descending into `When` branches so every assignment at
    * every condition depth is checked. */
  private def walkStmt(s: Stmt, ctx: Validator.Ctx,
                       errs: mutable.ListBuffer[Diagnostic]): Unit =
    s match
      case Stmt.Assign(target, expr, _, loc) =>
        // R6.2's exception: a narrowing `resize` forming the *entire* right-hand
        // side emits no selection, because Verilog truncates natively at the
        // target — so `x := (a + b).resize(8)` needs no bit-addressable operand.
        checkExpr(expr, loc, ctx, errs, wholeRhs = true)
        checkAssign(target, expr, loc, ctx, errs)
      case Stmt.When(cond, thenStmts, elseStmts, loc) =>
        // The condition is the `when`'s own; the arms carry their statements'
        // locations, so a bad operand three branches deep is reported at its
        // own line rather than at the outermost `when`.
        checkExpr(cond, loc, ctx, errs)
        thenStmts.foreach(walkStmt(_, ctx, errs))
        elseStmts.foreach(walkStmt(_, ctx, errs))
      case Stmt.Switch(skind, selector, cases, defaults, loc) =>
        // R22 — the selector and every case value are ordinary expressions and
        // are checked as such here. Whether a value *matches the selector* is
        // `SwitchPhase`'s question, not this phase's: this one asks only that
        // each expression is internally well-formed. A case value belongs to
        // its `is(...)`, not to the `switch`, so it reports at the arm.
        checkExpr(selector, loc, ctx, errs)
        cases.foreach { alternative =>
          alternative.values.foreach(checkExpr(_, alternative.loc, ctx, errs))
          alternative.stmts.foreach(walkStmt(_, ctx, errs))
        }
        defaults.foreach(_.foreach(walkStmt(_, ctx, errs)))
      case Stmt.Check(_, cond, _, loc) =>
        // The same treatment a `when` condition gets: the expression must be
        // internally well-formed. That it is one bit wide is not asked here,
        // for the same reason it is not asked of a `when` — R21 admits `Bool`
        // and a one-bit value alike as a condition.
        checkExpr(cond, loc, ctx, errs)
      case Stmt.MemWrite(_, addr, data, loc) =>
        // Unreachable as things stand: a memory write is recorded on the
        // `Memory` IR node's write ports, not into a driver block, so nothing
        // walked here is ever a `MemWrite`. Kept correct rather than deleted
        // because the shape is right the day memory ports are width-checked —
        // and `write` now records its call site, so the location is waiting.
        checkExpr(addr, loc, ctx, errs)
        checkExpr(data, loc, ctx, errs)

  /** Recursively check an expression's operands. A mismatch on `a + b` is
    * reported once at the `BinOp`, not again at the enclosing `:=`. */
  private def checkExpr(e: Expr, site: Loc, ctx: Validator.Ctx,
                        errs: mutable.ListBuffer[Diagnostic],
                        wholeRhs: Boolean = false): Unit =
    e match
      case Expr.Ref(_)            => ()   // widths come from the signal
      case Expr.Lit(_, _, _)      => ()
      case Expr.UnOp(op, arg)     =>
        checkExpr(arg, site, ctx, errs)
        checkUnOp(op, arg, site, ctx, errs)
      case Expr.BinOp(op, lhs, rhs) =>
        checkExpr(lhs, site, ctx, errs)
        checkExpr(rhs, site, ctx, errs)
        checkBinOp(op, lhs, rhs, site, ctx, errs)
      case Expr.Mux(sel, whenTrue, whenFalse) =>
        checkExpr(sel, site, ctx, errs)
        checkExpr(whenTrue, site, ctx, errs)
        checkExpr(whenFalse, site, ctx, errs)
        checkMuxArms(whenTrue, whenFalse, site, ctx, errs)
      case Expr.Slice(base, _, _) => checkExpr(base, site, ctx, errs)
      // R34 — a runtime-offset window: the base and the offset are both
      // expressions to check; the width is the constant the window states.
      case Expr.VarSlice(base, lo, _) => checkExpr(base, site, ctx, errs); checkExpr(lo, site, ctx, errs)
      case Expr.Index(base, idx)   => checkExpr(base, site, ctx, errs); checkExpr(idx, site, ctx, errs)
      case Expr.Field(base, name)  => checkExpr(base, site, ctx, errs)
      case Expr.Cat(parts)       =>
        parts.foreach(checkExpr(_, site, ctx, errs))
        parts.foreach(checkBitPatternPart("Cat", _, site, ctx, errs))
      case Expr.Repeat(_, inner) =>
        checkExpr(inner, site, ctx, errs)
        checkBitPatternPart("Fill", inner, site, ctx, errs)
      case Expr.Resize(to, inner) =>
        checkExpr(inner, site, ctx, errs)
        checkResize(to, inner, site, ctx, errs, wholeRhs)
      case Expr.TypeCast(_, inner) => checkExpr(inner, site, ctx, errs)
      case Expr.Cdc(_, inner, _) => checkExpr(inner, site, ctx, errs)

  // ---- R21 type admission and operand agreement ------------------------

  /** R21.5 — `Cat` and `Fill` are one surface: both emit a Verilog
    * concatenation, `{a, b}` and `{n{x}}`, and both admit exactly the bit
    * patterns R21's table admits. Three types are not one, and this is where
    * each of them was reaching the emitter.
    *
    * R13.1 — a **clock or reset** is not one. Without this check the type that
    * exists to keep a reset out of the data path was reachable through one-part
    * `Cat`, which is the shape somebody writes precisely when the operators
    * have refused (`Cat(rst)` where `!rst` was rejected). R13.5's `.asserted`
    * is the way through, and it says which level it means.
    *
    * R7.2 — an **aggregate** is a backstop rather than a rule. A bundle reads
    * as its packed bits, and `AggregateRead` has already written the
    * concatenation by the time this runs, so a `Cat` part that is still an
    * aggregate here is a read that pass did not reach. It is refused, because
    * the alternative is emitting a bundle's root name — `{a, a}` against a
    * module whose ports are `a_lo` and `a_hi` — which is a name no module
    * declares. */
  private def checkBitPatternPart(op: String, part: Expr, site: Loc,
                                  ctx: Validator.Ctx,
                                  errs: mutable.ListBuffer[Diagnostic]): Unit =
    val k = signedness(part, ctx)
    if k == Signedness.Clock || k == Signedness.Reset || k == Signedness.Aggregate then
      errs += admissionErr(op = op, e = part, k = k, site = site, ctx = ctx,
        allowed = "UInt, SInt, Bits, Bool",
        fix = if k == Signedness.Reset
              then "read the reset's active level explicitly with `.asserted`"
              else admissionFix(k))

  /** Unary admission (R21): `~` on any bit-typed operand, `!` on `Bool`, and
    * the reductions on any bit-typed operand. */
  private def checkUnOp(op: String, arg: Expr, site: Loc, ctx: Validator.Ctx,
                        errs: mutable.ListBuffer[Diagnostic]): Unit =
    val k = signedness(arg, ctx)
    val spelling = unOpSpelling(op)
    if ExprType.Op.isNot(op) then
      if k != Signedness.Bool then
        errs += admissionErr(op = spelling, e = arg, k = k, site = site, ctx = ctx,
          allowed = "Bool",
          fix = "compare explicitly, e.g. `(x === 0.U)`, or use `~` for a bitwise complement")
    else if !isBitTyped(k) then
      errs += admissionErr(op = spelling, e = arg, k = k, site = site, ctx = ctx,
        allowed = "UInt, SInt, Bits, Bool",
        fix = admissionFix(k))

  /** The signedness-mixing check, the type-admission check, and the operand
    * width-agreement check. The result width is not validated here (it is
    * checked against the target at `:=`); only that the operands are
    * *compatible*. */
  private def checkBinOp(op: String, lhs: Expr, rhs: Expr, site: Loc,
                         ctx: Validator.Ctx,
                         errs: mutable.ListBuffer[Diagnostic]): Unit =
    import ExprType.Op
    if Op.isShift(op) then checkShift(op, lhs, rhs, site, ctx, errs)
    else
      val ls = signedness(lhs, ctx)
      val rs = signedness(rhs, ctx)
      // R21's admission table, by operator class. `Bits` and `Bool` are
      // bitwise-and-equality types; ordering and arithmetic need a signedness
      // interpretation, which is exactly what those two withhold.
      val allowed =
        if Op.isBitwise(op) || Op.isEquality(op) then "UInt, SInt, Bits, Bool"
        else "UInt, SInt"
      val admits: Signedness => Boolean =
        if Op.isBitwise(op) || Op.isEquality(op) then isBitTyped
        else k => k == Signedness.Unsigned || k == Signedness.Signed
      if !admits(ls) || !admits(rs) then
        val offender = if !admits(ls) then ls else rs
        val at = if !admits(ls) then lhs else rhs
        errs += admissionErr(op = op, e = at, k = offender, site = site, ctx = ctx,
          allowed = allowed, fix = admissionFix(offender),
          other = Some((if !admits(ls) then rhs else lhs,
                        if !admits(ls) then rs else ls)))
        return
      // No implicit signedness conversion (R6).
      if ls != rs then
        errs += widthErr(site, exprLoc(lhs, ctx), exprLoc(rhs, ctx),
          summary = s"cannot $op ${signednessName(ls)} and ${signednessName(rs)} (no implicit signedness conversion)",
          related0 = signednessNote(lhs, ls, ctx),
          related1 = signednessNote(rhs, rs, ctx),
          fix = "convert explicitly with `.asSInt`/`.asUInt`",
          note = "operands must have the same kind")
        return
      // Operand width agreement (R6: no implicit width extension). `*` is the
      // one arithmetic operator exempt from it (R21.3): the product is
      // lossless, so differing widths hide no decision about which bits
      // survive. The shift amount, handled above, is the other exemption.
      if !Op.isMultiply(op) then checkWidthAgreement(op, lhs, rhs, site, ctx, errs)
      // R21.3 — a signed product emits a sign extension of each operand, so it
      // inherits R21.4's bit-addressable rule.
      if Op.isMultiply(op) && ls == Signedness.Signed then
        List(lhs, rhs).foreach { operand =>
          if !ExprType.isBitAddressable(operand) then
            errs += bitAddressableErr(operand, site, ctx,
              what = "a signed `*` sign-extends each operand before multiplying")
        }

  /** R21.1 — the shift rules. The amount is a separate operand: unsigned, of
    * any width, and exempt from width agreement, because it is not a value in
    * the result's number system at all. */
  private def checkShift(op: String, lhs: Expr, amount: Expr, site: Loc,
                         ctx: Validator.Ctx,
                         errs: mutable.ListBuffer[Diagnostic]): Unit =
    val ls = signedness(lhs, ctx)
    if ExprType.Op.isArithmeticShift(op) then
      if ls != Signedness.Signed then
        errs += admissionErr(op = op, e = lhs, k = ls, site = site, ctx = ctx,
          allowed = "SInt",
          fix = s"`>>>` is an arithmetic shift and needs a signed operand; write `x.asSInt >>> n`, or use `>>` for a logical shift")
        return
    else if !(ls == Signedness.Unsigned || ls == Signedness.Signed || ls == Signedness.Bits) then
      errs += admissionErr(op = op, e = lhs, k = ls, site = site, ctx = ctx,
        allowed = "UInt, SInt, Bits", fix = admissionFix(ls))
      return
    // The amount is unsigned. A negative shift has no meaning worth guessing.
    signedness(amount, ctx) match
      case Signedness.Signed =>
        errs += widthErr(site, exprLoc(amount, ctx), exprLoc(lhs, ctx),
          summary = s"shift amount is SInt (the amount is unsigned; a negative shift has no meaning)",
          related0 = signednessNote(amount, Signedness.Signed, ctx),
          related1 = signednessNote(lhs, ls, ctx),
          fix = "make the amount unsigned, e.g. `x >> n.asUInt`",
          note = "the shift amount must be unsigned")
      case Signedness.Unsigned | Signedness.Bits | Signedness.Bool => ()
      case other =>
        errs += admissionErr(op = s"$op (shift amount)", e = amount, k = other, site = site, ctx = ctx,
          allowed = "UInt, Bits, Bool", fix = admissionFix(other))

  /** R21 — the `Mux` arms agree in type and width, exactly as `+`'s operands
    * do; the result is the arms' type. */
  private def checkMuxArms(whenTrue: Expr, whenFalse: Expr, site: Loc,
                           ctx: Validator.Ctx,
                           errs: mutable.ListBuffer[Diagnostic]): Unit =
    val ts = signedness(whenTrue, ctx)
    val fs = signedness(whenFalse, ctx)
    if ts != fs then
      errs += widthErr(site, exprLoc(whenTrue, ctx), exprLoc(whenFalse, ctx),
        summary = s"Mux arms have different types: ${signednessName(ts)} and ${signednessName(fs)} (no implicit signedness conversion)",
        related0 = signednessNote(whenTrue, ts, ctx),
        related1 = signednessNote(whenFalse, fs, ctx),
        fix = "convert explicitly with `.asSInt`/`.asUInt` so both arms have one type",
        note = "both arms must have the same kind")
    else checkWidthAgreement("mux", whenTrue, whenFalse, site, ctx, errs)

  /** R21.4 — `resize` changes width and preserves kind. Narrowing and
    * sign-extending emit a bit-selection, so both require a bit-addressable
    * operand (R6.2) — except a narrowing `resize` that forms the entire
    * right-hand side of an assignment, where Verilog truncates natively. */
  private def checkResize(to: Int, inner: Expr, site: Loc, ctx: Validator.Ctx,
                          errs: mutable.ListBuffer[Diagnostic],
                          wholeRhs: Boolean): Unit =
    val k = signedness(inner, ctx)
    if !isBitTyped(k) then
      errs += admissionErr(op = s"resize($to)", e = inner, k = k, site = site, ctx = ctx,
        allowed = "UInt, SInt, Bits",
        fix = if k == Signedness.Aggregate then
                "read the aggregate's packed word first, e.g. `x.asBits`"
              else admissionFix(k))
      return
    val from = widthOf(inner, ctx).getOrElse(to)
    if to < from && !wholeRhs && !ExprType.isBitAddressable(inner) then
      errs += bitAddressableErr(inner, site, ctx,
        what = s"a narrowing `resize($to)` selects the low $to bits")
    else if to > from && k == Signedness.Signed && !ExprType.isBitAddressable(inner) then
      errs += bitAddressableErr(inner, site, ctx,
        what = s"a widening `resize($to)` of an SInt replicates its sign bit")

  /** R6 — operands must agree in width. */
  private def checkWidthAgreement(op: String, lhs: Expr, rhs: Expr, site: Loc,
                                  ctx: Validator.Ctx,
                                  errs: mutable.ListBuffer[Diagnostic]): Unit =
    for
      lw <- widthOf(lhs, ctx)
      rw <- widthOf(rhs, ctx)
      if lw != rw
    do
      errs += widthErr(site, exprLoc(lhs, ctx), exprLoc(rhs, ctx),
        summary = s"cannot $op ${typeName(lhs, ctx)} and ${typeName(rhs, ctx)}: widths $lw and $rw differ (no implicit extension)",
        related0 = widthNote(lhs, lw, ctx),
        related1 = widthNote(rhs, rw, ctx),
        fix = s"resize explicitly, e.g. `${exprSummary(lhs, ctx)}.resize($rw) $op ${exprSummary(rhs, ctx)}`",
        note = "operands must have equal width")

  /** Assignment width check. `x := expr` requires the expression's width to
    * equal `x`'s width; truncation is explicit via `resize` (R06.1's guard).
    *
    * `target` is an `Lhs` — a whole signal or a sub-element of an aggregate.
    * The target's type is resolved by walking the `Lhs` path against the root
    * signal's aggregate type, so `entries(2).addr := x` checks against the
    * leaf width, not the whole-Vec width. */
  private def checkAssign(target: Lhs, expr: Expr, site: Loc,
                          ctx: Validator.Ctx,
                          errs: mutable.ListBuffer[Diagnostic]): Unit =
    val ts = ctx.signal(target.rootId)
    val ttype = lhsType(target, ctx)
    val etype = typeOf(expr, ctx)
    val twidth = ttype.bitWidth
    val ewidth = widthOf(expr, ctx)
    if isAggregate(ttype) || isAggregate(etype) then
      if ttype != etype then
        errs += widthErr(site, ts.loc, exprLoc(expr, ctx),
          summary = s"cannot assign aggregate type $etype to `${ts.name}` of type $ttype (aggregate shapes must match exactly)",
          related0 = RelatedLoc(ts.loc, s"`${ts.name}` declared here as $ttype"),
          related1 = RelatedLoc(exprLoc(expr, ctx), s"source expression has type $etype"),
          fix = "assign aggregates with identical ordered Bundle fields and Vec dimensions",
          note = "aggregate shapes must match exactly")
      return
    ewidth match
      case Some(ew) if ew != twidth =>
        errs += widthErr(site, ts.loc, exprLoc(expr, ctx),
          summary = s"cannot assign width-$ew expression to `${ts.name}` of width $twidth (no implicit extension)",
          related0 = RelatedLoc(ts.loc, s"`${ts.name}` declared here with width $twidth"),
          related1 = widthNote(expr, ew, ctx),
          fix = s"resize explicitly, e.g. `${ts.name} := ${exprSummary(expr, ctx)}.resize($twidth)`",
          note = s"`${ts.name}` is $twidth bits wide, this is $ew")
      case _ => checkAssignKind(ts, ttype, etype, expr, site, ctx, errs)

  /** R6.5 — an assignment admits no implicit kind conversion, exactly as an
    * operator does not (R6). Until R6.5 this check did not exist: `checkAssign`
    * compared widths only, so `q := s` with `s: SInt(8)` and `q: UInt(8)` was
    * accepted silently while `q === s` was rejected — the assignment was the
    * one place in the language where a kind could change for free.
    *
    * Reported only when the widths already agree, so a single mistake yields a
    * single diagnostic and the width error keeps priority when both are wrong.
    *
    * `Bool` and a one-bit unsigned kind *are* interchangeable here, and only
    * here: see `kindsAgree` for why the exception is confined to a single bit.
    * At an operator the pair stays rejected — R21 admits `Bool` as a condition
    * rather than a number, and `===` has always said so — which is affordable
    * because `flag := 0` takes `Bool` from the target (R6.4) and `flag := True`
    * names it outright (R6.6), where `flag := 0.U` insisted on `UInt`. */
  private def checkAssignKind(ts: Signal, ttype: Type, etype: Type, expr: Expr,
                              site: Loc, ctx: Validator.Ctx,
                              errs: mutable.ListBuffer[Diagnostic]): Unit =
    val tk = signednessOf(ttype)
    val ek = signednessOf(etype)
    if !kindsAgree(tk, ek) then
      errs += widthErr(site, ts.loc, exprLoc(expr, ctx),
        summary = s"cannot assign ${signednessName(ek)} to `${ts.name}` of type ${signednessName(tk)} (an assignment admits no implicit kind conversion)",
        related0 = RelatedLoc(ts.loc, s"`${ts.name}` declared here as ${signednessName(tk)}"),
        related1 = signednessNote(expr, ek, ctx),
        fix = assignKindFix(ts.name, tk, ek, expr, ctx),
        note = s"`${ts.name}` is ${signednessName(tk)}, this is ${signednessName(ek)}")

  /** The fix for a kind mismatch at an assignment — an API that exists (R19),
    * chosen by what the target actually wants. */
  private def assignKindFix(target: String, tk: Signedness, ek: Signedness,
                            expr: Expr, ctx: Validator.Ctx): String =
    val e = exprSummary(expr, ctx)
    tk match
      case Signedness.Unsigned => s"convert explicitly, e.g. `$target := $e.asUInt`"
      case Signedness.Signed   => s"convert explicitly, e.g. `$target := $e.asSInt`"
      case Signedness.Bits     => s"convert explicitly, e.g. `$target := $e.asBits`"
      case Signedness.Bool     =>
        expr match
          // R6.6 — a one-bit constant has a name, so the fix for a suffixed
          // literal against a `Bool` names it rather than sending the user to
          // the bare `Int` that merely works.
          case Expr.Lit(v, _, _) if v == 0 || v == 1 =>
            s"name the one-bit constant, e.g. `$target := ${if v == 1 then "True" else "False"}`"
          case Expr.Lit(_, _, _) =>
            s"drop the suffix so the literal takes the target's kind, e.g. `$target := 0`"
          case _ =>
            s"compare explicitly, e.g. `$target := ($e === 1.U)`"
      case _ => s"convert explicitly with `.asUInt`/`.asSInt`/`.asBits`"

  /** Resolve the type an `Lhs` target names — the root signal's type, or the
    * field/element type reached by walking the path. R6.3 made this a shared
    * question: the builder resolves a whole-RHS literal against the same leaf
    * this checks the result against, so both read one implementation. */
  private[validate] def lhsType(lhs: Lhs, ctx: Validator.Ctx): Type =
    ExprType.lhsType(lhs, id => ctx.signal(id).typeOf)

  private def isAggregate(t: Type): Boolean = t match
    case Type.Bundle(_) | Type.Vec(_, _) => true
    case _ => false

object WidthPhase:
  /** Signedness class for the mixing and admission checks. `Bits` is distinct
    * from both signed and unsigned — it has a bit pattern but no arithmetic
    * interpretation (R6/R21). `Clock`/`Reset` are non-data (R13.1). */
  enum Signedness:
    case Unsigned, Signed, Bits, GrayCode, Bool, Clock, Reset, Aggregate

  /** The types R21 admits to the expression surface at all. `GrayCode` is
    * excluded by R5.3 (only its preserving operations), `Clock`/`Reset` by
    * R13.1, and an aggregate reaches the surface as its packed bits (R7.2)
    * (R7.2). */
  private[validate] def isBitTyped(k: Signedness): Boolean =
    k == Signedness.Unsigned || k == Signedness.Signed ||
    k == Signedness.Bits || k == Signedness.Bool

  /** R6.5 — whether an assignment's target and expression kinds agree.
    *
    * Exact agreement, with one exception: **`Bool` is interchangeable with a
    * 1-bit unsigned kind.** It reaches this check only when the widths already
    * match, so the exception is confined to a single bit, and it is the same
    * judgement `ExprType.withWidth` already makes — `Bool` is a 1-bit unsigned
    * value. Without it, `q := a(0)` would be an error, because a bit-select is
    * typed `Bool` while a 1-bit port is naturally declared `UInt(1)`; demanding
    * `a(0).asUInt` there would be ceremony protecting nothing, since the two
    * emit identically.
    *
    * `SInt` is deliberately outside the exception. A 1-bit `SInt` holds 0 or
    * -1, not 0 or 1, so it is a different value even at one bit — which is the
    * whole reason R6 refuses to mix signedness in the first place. */
  private[validate] def kindsAgree(target: Signedness, expr: Signedness): Boolean =
    if target == expr then true
    else
      def oneBitUnsigned(k: Signedness) =
        k == Signedness.Bool || k == Signedness.Unsigned || k == Signedness.Bits
      (target == Signedness.Bool || expr == Signedness.Bool) &&
        oneBitUnsigned(target) && oneBitUnsigned(expr)

  /** The signedness class of a type. */
  private[validate] def signednessOf(t: Type): Signedness = t match
    case Type.Bool      => Signedness.Bool
    case Type.UInt(_)   => Signedness.Unsigned
    case Type.SInt(_)   => Signedness.Signed
    case Type.Bits(_)   => Signedness.Bits
    case Type.GrayCode(_) => Signedness.GrayCode
    case Type.Clock     => Signedness.Clock
    case Type.Reset(_)  => Signedness.Reset
    case Type.Bundle(_) | Type.Vec(_, _) => Signedness.Aggregate

  /** The signedness of an expression's result. */
  private[validate] def signedness(e: Expr, ctx: Validator.Ctx): Signedness =
    signednessOf(typeOf(e, ctx))

  /** The type of an expression's result — R21.6's shared typer, reached
    * through the validator's signal table. Validation runs after freeze, so
    * every literal already carries a width. */
  private[validate] def typeOf(e: Expr, ctx: Validator.Ctx): Type =
    ExprType.typeOf(e, id => ctx.signal(id).typeOf)

  /** The bit width of an expression's result, when knowable. */
  private[validate] def widthOf(e: Expr, ctx: Validator.Ctx): Option[Int] =
    ExprType.widthOpt(e, id => ctx.signal(id).typeOf)

  /** The source location of an expression — the declaration line for a
    * `Ref`, the assignment line for a literal, the `NoLoc` for a synthetic
    * tree. This is the "used wrongly" location; the "declared" location is
    * carried by the signal referenced inside. */
  private[validate] def exprLoc(e: Expr, ctx: Validator.Ctx): Loc = e match
    case Expr.Ref(id) => ctx.signal(id).loc
    case _            => Loc.NoLoc

  /** A `note` for an operand's width + declaration line. */
  private[validate] def widthNote(e: Expr, w: Int, ctx: Validator.Ctx): RelatedLoc =
    e match
      case Expr.Ref(id) =>
        val s = ctx.signal(id)
        RelatedLoc(s.loc, s"`${s.name}` declared here with width $w")
      case _ =>
        RelatedLoc(Loc.NoLoc, s"anonymous expression of width $w")

  /** A `note` for an operand's signedness + declaration line. */
  private[validate] def signednessNote(e: Expr, ss: Signedness,
                                       ctx: Validator.Ctx): RelatedLoc =
    e match
      case Expr.Ref(id) =>
        val s = ctx.signal(id)
        RelatedLoc(s.loc, s"`${s.name}` declared here as ${signednessName(ss)}")
      case Expr.Lit(_, _, signed) =>
        RelatedLoc(Loc.NoLoc, s"literal of ${signednessName(ss)}")
      case _ =>
        RelatedLoc(Loc.NoLoc, s"anonymous ${signednessName(ss)} expression")

  private[validate] def signednessName(ss: Signedness): String = ss match
    case Signedness.Unsigned  => "UInt"
    case Signedness.Signed    => "SInt"
    case Signedness.Bits      => "Bits"
    case Signedness.GrayCode  => "GrayCode"
    case Signedness.Bool      => "Bool"
    case Signedness.Clock     => "Clock"
    case Signedness.Reset     => "Reset"
    case Signedness.Aggregate => "aggregate"

  /** The rule, and the reason, a type is not admitted to an operation. Cited
    * per offending type: `Bits` and `Bool` are R6/R21 (no arithmetic
    * interpretation), `GrayCode` is R5.3, `Clock`/`Reset` are R13.1, and
    * aggregates are R7.2. */
  private[validate] def nonArithReason(ss: Signedness): String = ss match
    case Signedness.Bits      => "Bits is a bit pattern with no signedness to interpret"
    case Signedness.Bool      => "Bool is a condition, not a number"
    case Signedness.GrayCode  => "GrayCode admits only its preserving operations"
    case Signedness.Clock     => "a clock used as data is a type error"
    case Signedness.Reset     => "a reset used as data is a type error"
    case Signedness.Aggregate => "an aggregate reads as its packed bits, and this one did not"
    case _                    => "not admitted here"

  /** The fix text for an inadmissible operand — an API that exists (R19). */
  private[validate] def admissionFix(ss: Signedness): String = ss match
    case Signedness.Bits | Signedness.Bool =>
      "choose an interpretation explicitly with `.asUInt` or `.asSInt`"
    case Signedness.GrayCode =>
      "convert with `.fromGray` and operate on the binary value"
    case Signedness.Clock | Signedness.Reset =>
      "a clock or reset is not data; drive logic from it through a register instead"
    case Signedness.Aggregate =>
      "name the aggregate's packed word, e.g. `x.asBits`"
    case _ => "convert explicitly with `.asUInt`/`.asSInt`"

  /** The R21 spelling of a unary op string (`UnOp("|")` is `orR`, not `|`). */
  private[validate] def unOpSpelling(op: String): String = op match
    case "~" => "~"
    case "!" => "!"
    case "|" => "orR"
    case "&" => "andR"
    case "^" => "xorR"
    case other => other

  /** R21 — an operand whose type the operator does not admit. Names the
    * operator, the offending type, what the operator does admit, and a fix
    * that refers to an API that exists. */
  private[validate] def admissionErr(op: String, e: Expr, k: Signedness, site: Loc,
                                     ctx: Validator.Ctx, allowed: String,
                                     fix: String,
                                     other: Option[(Expr, Signedness)] = None): Diagnostic =
    val otherLoc = other.map { case (oe, _) => exprLoc(oe, ctx) }.getOrElse(Loc.NoLoc)
    widthErr(site, exprLoc(e, ctx), otherLoc,
      summary = s"operator `$op` is not defined on `${signednessName(k)}` (${nonArithReason(k)}); it admits $allowed",
      related0 = signednessNote(e, k, ctx),
      related1 = other.map { case (oe, ok) => signednessNote(oe, ok, ctx) }
        .getOrElse(RelatedLoc(Loc.NoLoc, "")),
      fix = fix,
      note = s"`$op` admits $allowed here")

  /** R6.2 / R21.4 — an operation that emits a bit-selection, applied to an
    * anonymous tree. Same diagnostic and same fix as a direct bit-select:
    * bind the operand to a `val`. */
  private[validate] def bitAddressableErr(e: Expr, site: Loc, ctx: Validator.Ctx,
                                          what: String): Diagnostic =
    val primary = if site != Loc.NoLoc then site else exprLoc(e, ctx)
    Diagnostic(
      severity = Severity.Error,
      code = Code.BIT_SELECT_ANONYMOUS,
      summary = s"$what, which requires a named signal",
      primaryLoc = primary,
      primaryNote = Option.when(site != Loc.NoLoc)("this operand must be a named signal"),
      related = List(RelatedLoc(exprLoc(e, ctx), "anonymous expression used here"))
        .filter(_.loc != Loc.NoLoc),
      fix = Some("bind the operand to a `val` first, e.g. `val t = <expr>` then use `t`"))

  private[validate] def typeName(e: Expr, ctx: Validator.Ctx): String =
    typeOf(e, ctx) match
      case Type.Bool      => "Bool"
      case Type.UInt(w)   => s"UInt($w)"
      case Type.SInt(w)   => s"SInt($w)"
      case Type.Bits(w)   => s"Bits($w)"
      case Type.GrayCode(w) => s"GrayCode($w)"
      case Type.Clock     => "Clock"
      case Type.Reset(_)  => "Reset"
      case Type.Bundle(_) => "Bundle"
      case Type.Vec(n, e) => s"Vec($n, ${e.getClass.getSimpleName})"

  /** A short render of an expression for the suggested fix (`a.resize(8)`). */
  private[validate] def exprSummary(e: Expr, ctx: Validator.Ctx): String = e match
    case Expr.Ref(id) => ctx.signal(id).name
    case _            => "<expr>"

  /** Build a `WIDTH_MISMATCH` diagnostic with the standard shape.
    *
    * `site` is the statement that used the operands wrongly and is the primary
    * location whenever it exists — R19's caret belongs on the line the user
    * wrote, with `note` under it saying what that line got wrong. The
    * declarations are already carried by `related0`/`related1`, so pointing the
    * caret at one of them would spend the primary location on a fact the notes
    * repeat.
    *
    * `primary`/`other` are the fallback for a statement with no location: the
    * first operand's declaration, then the second's. That is the shape every
    * diagnostic here had before statement locations existed, and it is still
    * what a library helper's own assignments produce. `note` is dropped in that
    * case — "operands must have equal width" under a caret on a declaration
    * names the wrong line. */
  private[validate] def widthErr(site: Loc, primary: Loc, other: Loc,
                                 summary: String,
                                 related0: RelatedLoc, related1: RelatedLoc,
                                 fix: String,
                                 note: String): Diagnostic =
    val used = site != Loc.NoLoc
    Diagnostic(
      severity = Severity.Error,
      code = Code.WIDTH_MISMATCH,
      summary = summary,
      primaryLoc =
        if used then site
        else if primary != Loc.NoLoc then primary
        else other,
      related = List(related0, related1).filter(_.loc != Loc.NoLoc),
      fix = Some(fix),
      primaryNote = Option.when(used)(note))
end WidthPhase

package scalahdl.validate

import scalahdl.core.*
import SignalId.*
import scalahdl.diag.{Code, Diagnostic, Severity, RelatedLoc}
import scala.collection.mutable

/** R22 — What a `switch` must be true of, checked once the whole body has
  * elaborated.
  *
  * The timing is the point. `is` is an ordinary call, so alternatives may come
  * from a `for`, a `foreach`, or a helper, and two iterators that never meet in
  * the source can still produce the same case value. Checking as each `is`
  * arrives would catch only the duplicates one producer created; checking the
  * frozen IR catches every duplicate however it was produced, and does so after
  * R6.1/R21.7 literal resolution, so `is(0.U)` and `is(0.U(2))` are the same
  * value by then rather than two shapes.
  *
  * Four questions, in the order a reader would ask them:
  *
  *   1. is the selector something a case can select on at all (R22: bit-typed)?
  *   2. is each value an elaboration-time constant (R22: exact values only)?
  *   3. does each value have the selector's kind and width (R6: no implicit
  *      resize, no implicit signedness conversion)?
  *   4. do two values name the same bits?
  *
  * Canonical identity is selector kind + width + bit pattern, so two spellings
  * of one pattern — `3.U(2)` and `(1.U(2) | 2.U(2))` — are duplicates, which is
  * exactly what a reader of the emitted `case` would conclude.
  *
  * A switch is projected into one driver block per signal it writes, so the
  * same source switch is reached several times here; identical diagnostics are
  * collapsed at the end rather than reported once per driven signal. */
final class SwitchPhase extends Validator.Phase:
  import WidthPhase.{Signedness, signedness, isBitTyped, typeName, admissionFix}

  def run(ctx: Validator.Ctx): List[Diagnostic] =
    val errs = mutable.ListBuffer.empty[Diagnostic]
    ctx.module.drivers.foreach { db =>
      db.stmts.foreach(walkStmt(_, ctx, errs))
    }
    errs.result().distinct

  private def walkStmt(s: Stmt, ctx: Validator.Ctx,
                       errs: mutable.ListBuffer[Diagnostic]): Unit = s match
    case Stmt.When(_, thenStmts, elseStmts, _) =>
      thenStmts.foreach(walkStmt(_, ctx, errs))
      elseStmts.foreach(walkStmt(_, ctx, errs))
    case switch @ Stmt.Switch(_, _, cases, defaults, _) =>
      checkSwitch(switch, ctx, errs)
      cases.foreach(_.stmts.foreach(walkStmt(_, ctx, errs)))
      defaults.foreach(_.foreach(walkStmt(_, ctx, errs)))
    case _ => ()

  private def checkSwitch(switch: Stmt.Switch, ctx: Validator.Ctx,
                          errs: mutable.ListBuffer[Diagnostic]): Unit =
    if switch.kind.onConditions then return checkSelect(switch, ctx, errs)
    val selectorType = WidthPhase.typeOf(switch.selector, ctx)
    val selectorKind = signedness(switch.selector, ctx)
    if !isBitTyped(selectorKind) then
      errs += Diagnostic(
        severity = Severity.Error,
        code = Code.SWITCH_SELECTOR_TYPE,
        summary = s"`switch` selector has type ${typeName(switch.selector, ctx)}, which has no values to select on (the selector is bit-typed)",
        primaryLoc = switch.loc,
        related = selectorNote(switch.selector, ctx).toList,
        fix = Some(admissionFix(selectorKind)))
      return   // every value would mismatch a type that cannot be selected on

    // Canonical bit pattern → the alternative that first claimed it. Built as
    // the alternatives are walked, in elaboration order, so the *later* value
    // is the one reported and the earlier one is the related location (R22).
    val seen = mutable.LinkedHashMap.empty[BigInt, SwitchCase]

    switch.cases.foreach { alternative =>
      alternative.values.foreach { value =>
        val valueType = WidthPhase.typeOf(value, ctx)
        if valueType != selectorType then
          errs += Diagnostic(
            severity = Severity.Error,
            code = Code.SWITCH_VALUE_TYPE_MISMATCH,
            summary = s"case value has type ${typeName(value, ctx)} but the selector has type ${typeName(switch.selector, ctx)} (no implicit resize or signedness conversion)",
            primaryLoc = alternative.loc,
            related = (RelatedLoc(switch.loc, s"`switch` selector is ${typeName(switch.selector, ctx)}")
              :: selectorNote(switch.selector, ctx).toList),
            fix = Some(typeFix(valueType, selectorType)))
        else
          ExprConst.bits(value, id => ctx.signal(id).typeOf) match
            case None =>
              errs += Diagnostic(
                severity = Severity.Error,
                code = Code.SWITCH_VALUE_NOT_CONSTANT,
                summary = s"case value is not an elaboration-time constant (a case label is a fixed bit pattern, not a signal)",
                primaryLoc = alternative.loc,
                related = List(RelatedLoc(switch.loc, "in this `switch`")),
                fix = Some("use a literal or a constant expression, e.g. `is(0.U(2))`; to select on a computed value, compare it in a `when` instead"))
            case Some(pattern) =>
              seen.get(pattern) match
                case Some(first) =>
                  errs += Diagnostic(
                    severity = Severity.Error,
                    code = Code.SWITCH_DUPLICATE_VALUE,
                    summary = s"case value `${render(pattern, selectorType)}` is already covered by an earlier alternative (values are mutually exclusive)",
                    primaryLoc = alternative.loc,
                    related = List(
                      RelatedLoc(first.loc, s"`${render(pattern, selectorType)}` first selected here"),
                      RelatedLoc(switch.loc, "in this `switch`")),
                    fix = Some("remove the repeated value, or merge the two bodies into one alternative with `is(a, b) { ... }`"))
                case None => seen(pattern) = alternative
      }
    }

  /** A note naming the selector signal's declaration, when the selector is one.
    * An expression selector has no declaration to point at. */
  private def selectorNote(selector: Expr, ctx: Validator.Ctx): Option[RelatedLoc] =
    selector match
      case Expr.Ref(id) =>
        val s = ctx.signal(id)
        Some(RelatedLoc(s.loc, s"`${s.name}` declared here as ${WidthPhase.typeName(selector, ctx)}"))
      case _ => None

  /** The fix for a value the selector's type does not admit — a width change
    * and a kind change need different code, and saying which is the point. */
  private def typeFix(value: Type, selector: Type): String =
    if selector == Type.Bool then
      // R22 admits a bit-typed selector and `Bool` is one, but the DSL has no
      // `Bool` literal to name its two values with — and a two-way partition
      // is what `when`/`otherwise` already is (R12).
      "a `Bool` has two values: write `when(selector) { ... }.otherwise { ... }` instead"
    else if value.bitWidth != selector.bitWidth && sameKind(value, selector) then
      s"write the value at the selector's width, e.g. `${literalSpelling(selector)}`"
    else if value.bitWidth == selector.bitWidth then
      s"reinterpret the value explicitly with ${castFor(selector)}, or select on a value of its own kind"
    else
      s"give the value the selector's kind and width, e.g. `${literalSpelling(selector)}`"

  private def sameKind(a: Type, b: Type): Boolean = (a, b) match
    case (_: Type.UInt, _: Type.UInt) => true
    case (_: Type.SInt, _: Type.SInt) => true
    case (_: Type.Bits, _: Type.Bits) => true
    case (Type.Bool, Type.Bool)       => true
    case _                            => false

  private def literalSpelling(selector: Type): String = selector match
    case Type.SInt(w) => s"0.S($w)"
    case Type.Bits(w) => s"0.U($w).asBits"
    case other        => s"0.U(${other.bitWidth})"

  private def castFor(selector: Type): String = selector match
    case _: Type.SInt => "`.asSInt`"
    case _: Type.Bits => "`.asBits`"
    case _            => "`.asUInt`"

  /** Render a canonical pattern the way the emitted case label reads, so the
    * message and the Verilog agree. */
  private def render(pattern: BigInt, selector: Type): String = selector match
    case Type.SInt(w) => s"${ExprConst.signed(pattern, w)}.S($w)"
    case other        => s"$pattern.U(${other.bitWidth})"
end SwitchPhase

  // ---- R26 — selection on conditions -------------------------------------

  /** A `parallelCase` arm read as a ternary match: the bits `mask` fixes must
    * equal `value`, over the selector expression `sel`. Two cubes over the same
    * selector intersect exactly when every bit both of them fix agrees. */
  private final case class Cube(sel: Expr, mask: BigInt, value: BigInt)

  /** What R26 checks, and the order a reader would ask it in:
    *
    *   1. is every label one bit wide, which is all `case (1'b1)` can compare?
    *   2. (`parallelCase` only) is every label a cube, so the claim is
    *      decidable at all?
    *   3. (`parallelCase` only) do two of them match the same selector value?
    *
    * `priorityCase` stops after the first question, because it claims nothing
    * about its arms and Verilog's first-match reading is exactly what it means.
    */
  private def checkSelect(switch: Stmt.Switch, ctx: Validator.Ctx,
                          errs: mutable.ListBuffer[Diagnostic]): Unit =
    val what = if switch.kind == SelectKind.OneHot then "parallelCase" else "priorityCase"
    var widthsOk = true
    switch.cases.foreach { arm =>
      arm.values.foreach { cond =>
        val w = ExprType.widthOpt(cond, id => ctx.signal(id).typeOf)
        if !w.contains(1) then
          widthsOk = false
          errs += Diagnostic(
            severity = Severity.Error,
            code = Code.SELECT_CONDITION_WIDTH,
            summary = s"`on` names a ${w.map(n => s"$n-bit").getOrElse("width-less")} expression, and a condition is one bit",
            primaryLoc = arm.loc,
            related = List(RelatedLoc(switch.loc, s"in this `$what`")),
            fix = Some("compare it — `on(x === y)` — or take the bit you meant, `on(x(3))`"))
      }
    }
    if switch.kind != SelectKind.OneHot || !widthsOk then return

    // Every arm must be readable as a cube, or the claim cannot be decided and
    // `parallelCase` would be a promise rather than a proof.
    val read = switch.cases.flatMap(arm => arm.values.map(v => (arm, v, asCube(v, ctx))))
    read.foreach {
      case (arm, cond, None) =>
        errs += Diagnostic(
          severity = Severity.Error,
          code = Code.SELECT_ARM_NOT_CUBE,
          summary = "a `parallelCase` arm is not a masked compare, so \"at most one holds\" cannot be decided",
          primaryLoc = arm.loc,
          related = List(RelatedLoc(switch.loc, "in this `parallelCase`")),
          fix = Some("write the arm as `(sel & mask) === value` or `sel === value` over one common selector, or use `priorityCase`, which claims nothing"))
      case _ => ()
    }
    val cubes = read.collect { case (arm, _, Some(c)) => (arm, c) }
    if cubes.sizeCompare(read) != 0 then return

    // One selector for the whole construct: two cubes over different
    // expressions cannot be compared, and calling that disjoint would be
    // deciding the claim by not looking at it.
    val selectors = cubes.map(_._2.sel).distinct
    if selectors.sizeCompare(1) > 0 then
      errs += Diagnostic(
        severity = Severity.Error,
        code = Code.SELECT_ARM_NOT_CUBE,
        summary = s"a `parallelCase`'s arms compare ${selectors.size} different expressions, and disjointness is a question about one",
        primaryLoc = switch.loc,
        related = cubes.map(_._1).distinct.take(4).map(a => RelatedLoc(a.loc, "arm here")),
        fix = Some("mask one selector in every arm, or use `priorityCase`"))
      return

    // Disjointness, decided: two cubes overlap unless some bit both fix
    // disagrees. The claim is about *arms*, so this compares one arm's cubes
    // against earlier arms' and never against its own — two labels of one
    // `on` are an or, and an or of overlapping conditions is not a conflict.
    // Reported once per pair, with a value that reaches both, which is the
    // fact a reader needs to see which encoding was claimed twice.
    val seen = mutable.ListBuffer.empty[(SwitchCase, Cube)]
    switch.cases.foreach { arm =>
      val mine = cubes.collect { case (a, c) if a eq arm => c }
      mine.foreach { c =>
        seen.find((_, prior) => ((prior.value ^ c.value) & prior.mask & c.mask) == 0) match
          case Some((first, prior)) =>
            val witness = (prior.value & prior.mask) | (c.value & c.mask)
            errs += Diagnostic(
              severity = Severity.Error,
              code = Code.SELECT_ARMS_OVERLAP,
              summary = f"two `parallelCase` arms both match ${witness}%#x, and the construct claims at most one can",
              primaryLoc = arm.loc,
              related = List(RelatedLoc(first.loc, "first arm matching it here"),
                             RelatedLoc(switch.loc, "in this `parallelCase`")),
              fix = Some("narrow one of the two masks, or use `priorityCase`, where the earlier arm simply wins"))
          case None => ()
      }
      mine.foreach(c => seen += ((arm, c)))
    }

  /** Read one condition as a cube. `(sel & m) === v` and `sel === v` are the
    * two shapes a decode PLA writes; the mask and the value must be constant,
    * the selector need not be. */
  private def asCube(cond: Expr, ctx: Validator.Ctx): Option[Cube] =
    def const(e: Expr): Option[BigInt] = ExprConst.bits(e, id => ctx.signal(id).typeOf)
    def full(sel: Expr): Option[BigInt] =
      ExprType.widthOpt(sel, id => ctx.signal(id).typeOf).map(w => (BigInt(1) << w) - 1)
    cond match
      case Expr.BinOp("==", lhs, rhs) =>
        val (expr, valueSide) = const(rhs) match
          case Some(_) => (lhs, rhs)
          case None    => (rhs, lhs)
        for
          v <- const(valueSide)
          cube <- expr match
            case Expr.BinOp("&", a, b) =>
              (const(b), const(a)) match
                case (Some(m), _) => Some(Cube(a, m, v))
                case (_, Some(m)) => Some(Cube(b, m, v))
                case _            => None
            case sel => full(sel).map(m => Cube(sel, m, v))
        yield cube
      case _ => None

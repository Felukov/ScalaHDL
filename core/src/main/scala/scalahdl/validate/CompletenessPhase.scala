package scalahdl.validate

import scalahdl.core.*
import SignalId.*
import scalahdl.diag.{Code, Diagnostic, Severity, RelatedLoc}
import scala.collection.mutable

/** R11 — Combinational completeness. A combinational (wire) signal must be
  * driven on every path; incomplete assignment is an elaboration error naming
  * the signal, the uncovered condition, and the declaration line. The fix is
  * an explicit default before the conditional (last-wins in source order).
  *
  * R11.1 — registers are exempt: an unassigned path on a `Reg` means "hold
  * value", no error, no default required. The asymmetry is the teaching point.
  *
  * Mechanism. Each wire's driver block is a list of statements in source
  * order. `When(cond, then, else)` is exhaustive *by construction* of the
  * `when`/`elsewhen`/`otherwise` chain: an `elsewhen` lands as a nested
  * `When` inside the outer's else arm; `otherwise` writes the else arm
  * directly. So the "uncovered condition" is always the else branch of a
  * `When` that has an empty else arm.
  *
  * A top-level unconditional `:=` (the default-before-conditional idiom)
  * satisfies completeness: it writes the signal on every path before any
  * `when` refines it (last-wins), so even a `When` with an empty else still
  * covers the signal — the default already drove it.
  *
  * The walker tracks, per signal, whether an unconditional write has
  * occurred *before or alongside* the conditional structure. If yes, every
  * `When` else-gap is covered by that default. If no, an else-gap is a
  * missing assignment. */
final class CompletenessPhase extends Validator.Phase:

  def run(ctx: Validator.Ctx): List[Diagnostic] =
    val errs = mutable.ListBuffer.empty[Diagnostic]
    ctx.module.drivers.foreach { db =>
      val sig = ctx.signal(db.signalId)
      if !sig.isReg then   // R11.1: registers are exempt
        checkWire(sig, db.stmts, ctx, errs)
    }
    errs.result()

  /** Check one wire's driver block for an uncovered path. */
  private def checkWire(sig: Signal, stmts: List[Stmt],
                        ctx: Validator.Ctx,
                        errs: mutable.ListBuffer[Diagnostic]): Unit =
    sig.typeOf match
      case Type.Bundle(_) | Type.Vec(_, _) =>
        // Completeness is a leaf property for aggregates. A write to one field
        // or one constant Vec element cannot accidentally cover its siblings.
        Leaf.leaves(sig.typeOf).foreach { leaf =>
          if !coveredAfter(stmts, sig.id, leaf, initiallyCovered = false, ctx) then
            errs += Diagnostic(
              severity = Severity.Error,
              code = Code.COMBINATIONAL_INCOMPLETE,
              summary = s"`${sig.name}${Leaf.render(leaf)}` is not driven on every path",
              primaryLoc = sig.loc,
              related = List(RelatedLoc(sig.loc, s"aggregate `${sig.name}` declared here")),
              fix = Some(s"assign `${sig.name}${Leaf.render(leaf)}` unconditionally before refining it in `when` blocks"))
        }
      case _ =>
        if !hasUnconditionalWrite(stmts, sig.id) then
          scanGaps(sig, stmts, ctx, errs, pathCond = None)

  /** The default-before-conditional idiom: an unconditional `:=` at this level
    * covers every path below it (last-wins in source order), so nothing under
    * it can leave a gap. Asked at the top of a driver block and again inside
    * each `switch` alternative, which is the same statement list one level in. */
  private def hasUnconditionalWrite(stmts: List[Stmt], id: SignalId): Boolean =
    stmts.exists {
      case Stmt.Assign(Lhs.Sig(target), _, _, _) => target == id
      case _ => false
    }

  /** Descend into one branch of a conditional, unless the branch already
    * assigns the signal unconditionally — in which case nothing under it can
    * leave a gap, and reporting one would be a false positive. */
  private def scanArm(sig: Signal, stmts: List[Stmt], ctx: Validator.Ctx,
                      errs: mutable.ListBuffer[Diagnostic],
                      pathCond: Option[Expr]): Unit =
    if !hasUnconditionalWrite(stmts, sig.id) then
      scanGaps(sig, stmts, ctx, errs, pathCond)

  /** Walk the statement list; a `When` with an empty else arm and no
    * enclosing default is an uncovered path. Descend into non-empty arms
    * so a nested `when` gap is reported with the nested condition. */
  private def scanGaps(sig: Signal, stmts: List[Stmt],
                      ctx: Validator.Ctx,
                      errs: mutable.ListBuffer[Diagnostic],
                      pathCond: Option[Expr]): Unit =
    stmts.foreach {
      case Stmt.Assign(Lhs.Sig(id), _, _, _) if id == sig.id =>
        // An unconditional write at this level covers everything below it
        // on this path; no need to descend further for gaps *here*, but
        // sibling `when`s may still have gaps. We just record the cover.
        ()
      case Stmt.When(cond, thenStmts, elseStmts, wloc) =>
        // Descend into the then arm — gaps there are uncovered under `cond`.
        // The default-before-conditional idiom applies at every level, not
        // only the outermost: an unconditional write inside this arm covers
        // the arm's own nested conditionals, exactly as one at the top of the
        // driver block covers the whole block.
        scanArm(sig, thenStmts, ctx, errs, Some(cond))
        if elseStmts.nonEmpty then
          // A non-empty else arm: descend with the negation context.
          scanArm(sig, elseStmts, ctx, errs, pathCond)
        else
          // Empty else arm and no default: the signal is unassigned when
          // this `when`'s condition is false. Report it.
          errs += uncoveredErr(sig, cond, wloc, ctx)
      case Stmt.Switch(skind, selector, cases, defaults, sloc) =>
        // R22 — a switch is exhaustive when a `default` catches everything the
        // alternatives do not, or when the alternatives themselves name every
        // value the selector can take. Coverage of *this signal* additionally
        // needs each reachable alternative to assign it.
        //
        // `coveredAfter` is the exact answer for one alternative, so there is
        // nothing to descend into: an alternative it calls covered has no gap
        // anywhere inside, and one it calls uncovered is reported here, at the
        // `is` the reader wrote, rather than at some nested `when` within it.
        val uncovered = cases.filterNot(alternative =>
          coveredAfter(alternative.stmts, sig.id, Nil, initiallyCovered = false, ctx))
        val restGap = defaults match
          case Some(defaultStmts) =>
            Option.unless(coveredAfter(defaultStmts, sig.id, Nil, initiallyCovered = false, ctx))(
              "`default`")
          case None =>
            Option.unless(coversValueSpace(skind, selector, cases, ctx))(
              if skind.onConditions then "the conditions no alternative names"
              else "the values no alternative names")
        if uncovered.nonEmpty || restGap.isDefined then
          errs += uncoveredSwitchErr(sig, selector, uncovered, restGap, sloc, ctx)
      case _ => ()
    }

  /** R22 — whether the alternatives cover the selector's entire finite value
    * space. Exact, and bounded by construction: the count of distinct written
    * patterns is compared against `2^w` as a number, so a 32-bit selector costs
    * one `BigInt` comparison rather than four billion set insertions.
    *
    * A value that is not an elaboration-time constant contributes nothing;
    * `SwitchPhase` has already rejected it, and a failing phase halts the run
    * before this one, so that case is unreachable in practice rather than
    * silently permissive. */
  /** R22 — do the alternatives name every value the selector can take?
    *
    * R26 — never, when the alternatives are conditions. The selector there is
    * the constant one and the labels are expressions, so the question the
    * count answers is not the question being asked: "these eight hundred cubes
    * partition the surface" is a fact about the design, and a `default` is how
    * a construct says it covers the rest. */
  private def coversValueSpace(kind: SelectKind, selector: Expr, cases: List[SwitchCase],
                               ctx: Validator.Ctx): Boolean =
    if kind.onConditions then return false
    val types: SignalId => Type = id => ctx.signal(id).typeOf
    ExprType.widthOpt(selector, types) match
      case None => false
      case Some(width) =>
        val patterns = cases.iterator.flatMap(_.values).flatMap(ExprConst.bits(_, types)).toSet
        BigInt(patterns.size) == (BigInt(1) << width)

  private def targetCovers(lhs: Lhs, root: SignalId, leaf: List[LeafSeg]): Boolean =
    Leaf.path(lhs).exists { case (id, path) => id == root && leaf.startsWith(path) }

  /** Evaluate sequential last-wins coverage for one aggregate leaf. A when
    * covers the leaf only when both arms leave it covered, unless an earlier
    * unconditional assignment already supplied a default. */
  private def coveredAfter(stmts: List[Stmt], root: SignalId, leaf: List[LeafSeg],
                           initiallyCovered: Boolean, ctx: Validator.Ctx): Boolean =
    stmts.foldLeft(initiallyCovered) { (covered, stmt) => stmt match
      case Stmt.Assign(target, _, _, _) if targetCovers(target, root, leaf) => true
      case Stmt.When(_, thenStmts, elseStmts, _) =>
        coveredAfter(thenStmts, root, leaf, covered, ctx) &&
          coveredAfter(elseStmts, root, leaf, covered, ctx)
      case Stmt.Switch(skind, selector, cases, defaults, _) =>
        // Every alternative must leave the leaf covered, and so must the path
        // for values no alternative names — the `default` when there is one,
        // and otherwise an implicit empty arm that exists only when the
        // alternatives fall short of the selector's value space. An implicit
        // arm assigns nothing, so it leaves coverage exactly as it found it,
        // which is the same shape as `When`'s empty else.
        val armsCover = cases.forall(alternative =>
          coveredAfter(alternative.stmts, root, leaf, covered, ctx))
        val restCovers = defaults match
          case Some(defaultStmts) => coveredAfter(defaultStmts, root, leaf, covered, ctx)
          case None => coversValueSpace(skind, selector, cases, ctx) || covered
        armsCover && restCovers
      case _ => covered
    }

  /** Build the `COMBINATIONAL_INCOMPLETE` diagnostic. The uncovered condition
    * is rendered from the `when`'s condition expression; the `when`'s source
    * line is carried when available, the signal's declaration line always. */
  private def uncoveredErr(sig: Signal, cond: Expr, wloc: Loc,
                           ctx: Validator.Ctx): Diagnostic =
    val condStr = renderCond(cond, ctx)
    val summary =
      s"`${sig.name}` is unassigned when `${condStr}` is false (combinational completeness)"
    val related =
      val decl = RelatedLoc(sig.loc, s"`${sig.name}` declared here")
      val when = if wloc != Loc.NoLoc then
        List(RelatedLoc(wloc, s"`when ($condStr)` here: else branch missing"))
      else Nil
      decl :: when
    Diagnostic(
      severity = Severity.Error,
      code = Code.COMBINATIONAL_INCOMPLETE,
      summary = summary,
      primaryLoc = sig.loc,
      related = related,
      fix = Some(s"add a default before the `when`, e.g. `${sig.name} := <default>`"))

  /** R11/R22 — the switch a wire falls through. One diagnostic per switch,
    * naming every alternative that leaves the signal unassigned and, when the
    * gap is the unnamed values, saying so — a reader fixing this needs the
    * whole list, not the first hole in it.
    *
    * The fix names both ways out, because they are genuinely different designs:
    * a default *before* the switch says "this is the resting value", a
    * `default` arm says "this is what the other states do". */
  private def uncoveredSwitchErr(sig: Signal, selector: Expr,
                                 uncovered: List[SwitchCase], restGap: Option[String],
                                 sloc: Loc, ctx: Validator.Ctx): Diagnostic =
    val selectorStr = renderCond(selector, ctx)
    // R24 — when the selector was declared from an encoding, a case value is
    // reported as `8 (AluOp.Or)`. The bit pattern alone is what the reader has
    // to translate back by hand, and the encoding is exactly the table they
    // would translate it with.
    val encoding = selector match
      case Expr.Ref(id) => ctx.signal(id).encoding
      case _            => None
    def caseValue(v: Expr): String = v match
      case Expr.Lit(value, _, _) =>
        encoding.flatMap(_.qualified(value)) match
          case Some(member) => s"$value ($member)"
          case None         => value.toString
      case other => renderCond(other, ctx)
    def arm(a: SwitchCase): String = s"is(${a.values.map(caseValue).mkString(", ")})"
    val what = (uncovered.map(a => s"`${arm(a)}`") ++ restGap).mkString(", ")
    val restNote = restGap.map {
      case "`default`" => s"`switch ($selectorStr)`: its `default` does not assign `${sig.name}`"
      case _ => s"`switch ($selectorStr)`: no `default`, and the alternatives do not name every value `$selectorStr` can take"
    }
    val related =
      RelatedLoc(sig.loc, s"`${sig.name}` declared here") ::
      uncovered.map(a => RelatedLoc(a.loc, s"`${arm(a)}` does not assign `${sig.name}`")) ++
      restNote.map(note => RelatedLoc(sloc, note)).toList
    Diagnostic(
      severity = Severity.Error,
      code = Code.COMBINATIONAL_INCOMPLETE,
      summary = s"`${sig.name}` is unassigned on some path through `switch ($selectorStr)`: $what",
      primaryLoc = sig.loc,
      related = related.filter(_.loc != Loc.NoLoc),
      fix = Some(s"assign `${sig.name}` before the `switch`, e.g. `${sig.name} := <default>`, or assign it in every alternative and add a `default`"))

  /** Render a condition expression for the message. Refs → signal names;
    * everything else → a short structural form. The message only needs to be
    * stable; it is not the R19 excerpt. */
  private def renderCond(e: Expr, ctx: Validator.Ctx): String = e match
    case Expr.Ref(id)    => ctx.signal(id).name
    case Expr.Lit(v, _, _) => v.toString
    case Expr.UnOp(op, a) => s"$op${renderCond(a, ctx)}"
    case Expr.BinOp(op, a, b) =>
      s"${renderCond(a, ctx)} $op ${renderCond(b, ctx)}"
    case _ => "<cond>"
end CompletenessPhase

package scalahdl.validate

import scalahdl.core.*
import scalahdl.core.CombGraph.{Edge, Node}
import scalahdl.diag.{Code, Diagnostic, RelatedLoc, Severity}

/** R28 — a combinational cycle is an elaboration error.
  *
  * R11 asks whether a wire is driven on every path; this asks whether what
  * drives it settles. They are the same sentence about a wire not being a
  * register one step apart, and R11.1's exemption is exactly why `y := y + 1`
  * has to stay legal on the declaration one line up.
  *
  * The work is all in `CombGraph`; a ring is a back edge in it. What is left
  * here is the message, and the message is the point: Verilog lint finds the
  * same rings and reports them as emitted wire names and `ASSIGNW` nodes,
  * while every hop here was written by a named `val` (R1) in a statement
  * carrying a `Loc` (R17).
  *
  * It runs after R11 so that a wire with no driver at all is reported as the
  * missing default it is, rather than as a ring through whatever else reads
  * it. */
final class CyclePhase extends Validator.Phase:

  def run(ctx: Validator.Ctx): List[Diagnostic] =
    ctx.comb.cycles.map(ring => cycleError(ctx, ring))

  private def cycleError(ctx: Validator.Ctx, ring: List[Edge]): Diagnostic =
    val graph = ctx.comb
    val start = graph.render(ring.head.from)
    val chain = (start :: ring.map(edge => graph.render(edge.to))).mkString(" -> ")
    // The closing edge is the last one found, and it is the one a reader can
    // most usefully delete: everything before it was reachable already.
    val closing = ring.last
    val members = ring.map(edge => s"`${graph.render(edge.to)}`").distinct.mkString(", ")
    val related = ring.map(edge =>
      RelatedLoc(edge.loc,
        s"`${graph.render(edge.to)}` needs `${graph.render(edge.from)}`" +
          (if edge.via.isEmpty then "" else s" (${edge.via})")))
      .filter(_.loc != Loc.NoLoc)
    val declaration = declarationOf(ctx, ring.head.from).toList
    Diagnostic(
      severity = Severity.Error,
      code = Code.COMBINATIONAL_CYCLE,
      summary =
        if ring.length == 1 then
          s"`$start` is computed from itself (a combinational value must settle)"
        else
          s"`$start` is computed from itself: $chain (a combinational value must settle)",
      primaryLoc = if closing.loc != Loc.NoLoc then closing.loc else ring.head.loc,
      related = declaration ++ related,
      primaryNote = Some(s"this closes the ring $chain"),
      fix = Some(
        s"break the ring with a register, or compute one of $members from a value " +
          "that does not depend on it"))

  private def declarationOf(ctx: Validator.Ctx, node: Node): Option[RelatedLoc] = node match
    case Node.Value(id, _) => ctx.signals.get(id).collect {
      case signal if signal.loc != Loc.NoLoc =>
        RelatedLoc(signal.loc, s"`${signal.name}` declared here")
    }
    case _ => None

package scalahdl.emit

import scalahdl.core.*
import scalahdl.core.CombGraph.{Cost, Edge, Graph, Node}

/** R29 — the logic-level report, `<Module>.levels.rpt`, written beside the
  * Verilog exactly as the R5.1 CDC report and the R25 FSM report are.
  *
  * The number it exists to surface is one a synthesiser would otherwise be the
  * first to know. A design's synthesis run found a 749-word microcode store
  * emitted as logic, owning the 82 ns critical path, and the diagnosis is
  * written in this library's own rules: the store is logic *because R12 gives
  * every signal its own driver block*. Nothing about that needed a fitter — it
  * needed something to count with.
  *
  * And R2 is why nothing else can count. An anonymous expression tree is
  * inlined at every use site and wire count equals `val` count, so neither the
  * source's handful of `val`s nor the output's handful of `assign`s reads as
  * deep. The trees and the chain between two registers are both held here.
  *
  * It is not a timing report, and it says so where a reader will see it: no
  * device family, no placement, no routing, no nanoseconds. It ranks. */
object LevelReport:

  /** Full paths are printed for this many cones; the table lists this many
    * times four, and says what it dropped. A truncation the reader cannot see
    * reads as "there was nothing else". */
  private val Detailed = 8
  private val Listed = 32

  def render(module: Module, graph: Graph): String =
    val sb = new StringBuilder
    sb.append(s"Logic levels: ${module.name}\n\n")
    sb.append(modelSection)
    sb.append(assumptionSection(graph))
    val ranked = rank(graph)
    if ranked.isEmpty then
      sb.append("Deepest cones\n\n  (none: no combinational logic between any two endpoints)\n")
      return sb.toString

    sb.append(s"Deepest cones (${math.min(ranked.length, Listed)} of ${ranked.length})\n\n")
    sb.append("   #  endpoint                                  hops  cells  from\n")
    ranked.take(Listed).zipWithIndex.foreach { case ((sink, cost, path), index) =>
      val from = path.headOption.map(edge => graph.render(edge.from)).getOrElse("—")
      sb.append(f"  ${index + 1}%2d  ${cell(graph.render(sink), 40)}%s  ${cost.hops}%4d  ${cost.cells}%5d  $from%s\n")
    }
    if ranked.length > Listed then
      sb.append(s"  … ${ranked.length - Listed} shallower cones not listed\n")

    sb.append("\n")
    ranked.take(Detailed).zipWithIndex.foreach { case ((sink, cost, path), index) =>
      sb.append(detail(module, graph, index + 1, sink, cost, path))
    }
    sb.toString

  /** The design-level roll-up: which module owns the deepest cone. A cone that
    * crosses hierarchy is already counted in the parent, because an instance
    * edge carries the child's summarised depth — so this ranks what the
    * per-module files each found rather than recomputing anything. */
  def rollUp(top: String, modules: List[(Module, Graph)]): String =
    val sb = new StringBuilder
    sb.append(s"Logic levels: $top (design)\n\n")
    sb.append(modelSection)
    val assumptions = modules.flatMap((_, graph) => graph.assumptions).distinct
    sb.append(assumptionText(assumptions))
    sb.append("Deepest cone per module\n\n")
    sb.append("  module                                    hops  cells  endpoint\n")
    val ranked = modules.map { case (module, graph) =>
      (module.name, rank(graph).headOption.map((sink, cost, _) => (graph.render(sink), cost)))
    }.sortBy { case (name, best) =>
      (-best.map(_._2.cells).getOrElse(0), -best.map(_._2.hops).getOrElse(0), name)
    }
    ranked.foreach {
      case (name, Some((endpoint, cost))) =>
        sb.append(f"  ${cell(name, 40)}%s  ${cost.hops}%4d  ${cost.cells}%5d  $endpoint%s\n")
      case (name, None) =>
        sb.append(f"  ${cell(name, 40)}%s     —      —  (no combinational path)\n")
    }
    sb.append("\nEach module's own report carries the paths behind these numbers. A cone\n")
    sb.append("that crosses an instance is counted in the parent, one hop for the child,\n")
    sb.append("carrying the depth its own summary reported.\n")
    sb.toString

  // ------------------------------------------------------------- sections

  private def modelSection: String =
    val sb = new StringBuilder
    sb.append("Model: hops count edges between named signals; cells weight the operators\n")
    sb.append("between them. An estimate for ranking, not a timing result — nothing here\n")
    sb.append("knows the device family, the placement or the routing.\n\n")
    CombGraph.Weight.model.foreach { case (weight, what) =>
      sb.append(f"  ${cell(weight, 14)}%s $what%s\n")
    }
    sb.append("\n")
    sb.toString

  private def assumptionSection(graph: Graph): String = assumptionText(graph.assumptions)

  private def assumptionText(assumptions: List[String]): String =
    if assumptions.isEmpty then
      "Assumed: nothing — every instance in this cone was analysed.\n\n"
    else
      "Assumed:\n" + assumptions.map(line => s"  - $line\n").mkString + "\n"

  // --------------------------------------------------------------- ranking

  /** Every endpoint that has combinational logic in front of it, deepest
    * first. An endpoint reached through wiring alone — an output that is a
    * register read, a clock pin — is left out: it is a fact about the design,
    * not a finding about its depth. */
  private def rank(graph: Graph): List[(Node, Cost, List[Edge])] =
    graph.sinks.toList
      .map(sink => (sink, graph.depths.get(sink).map(_._1).getOrElse(Cost.zero)))
      .filter((_, cost) => cost.cells > 0)
      .map((sink, cost) => (sink, cost, graph.pathTo(sink)))
      .sortBy((sink, cost, _) => (-cost.cells, -cost.hops, graph.render(sink)))

  private def detail(module: Module, graph: Graph, index: Int, sink: Node,
                     cost: Cost, path: List[Edge]): String =
    val sb = new StringBuilder
    val from = path.headOption.map(edge => graph.render(edge.from)).getOrElse("—")
    sb.append(s"  #$index  $from -> ${graph.render(sink)}")
    sb.append(s"   ${plural(cost.hops, "hop")}, ${plural(cost.cells, "cell")}\n")
    path.headOption.foreach { first =>
      sb.append(f"        ${cell(graph.render(first.from), 32)}%s ${where(module, first.from)}%s\n")
    }
    path.foreach { edge =>
      val via = if edge.via.isEmpty then "wire" else edge.via
      sb.append(f"     -> ${cell(graph.render(edge.to), 32)}%s ${cell(where(module, edge.to), 24)}%s $via%s")
      sb.append(if edge.cost.cells > 0 then s"  +${edge.cost.cells}\n" else "\n")
    }
    sb.append("\n")
    sb.toString

  /** Where the reader should look: the statement that wrote the hop, and for
    * the first node of a chain its declaration. */
  private def where(module: Module, node: Node): String = node match
    case Node.Value(id, _)    => declaredAt(module, id)
    case Node.Next(id, _)     => declaredAt(module, id)
    case Node.Pin(instance, _) =>
      module.instances.find(_.name == instance).map(i => shortLoc(i.loc)).getOrElse("—")
    case Node.Write(memory, _) =>
      module.memories.find(_.name == memory).map(m => shortLoc(m.loc)).getOrElse("—")

  private def declaredAt(module: Module, id: SignalId): String =
    module.signals.find(_.id == id).map(signal => shortLoc(signal.loc)).getOrElse("—")

  private def shortLoc(loc: Loc): String =
    if loc == Loc.NoLoc then "—"
    else
      val slash = loc.file.lastIndexOf('/')
      val file = if slash >= 0 then loc.file.substring(slash + 1) else loc.file
      s"$file:${loc.line}"

  private def plural(n: Int, word: String): String =
    if n == 1 then s"$n $word" else s"$n ${word}s"

  /** Left-justify, and never truncate a name into ambiguity: a column that has
    * to grow is better than two rows that read alike. */
  private def cell(text: String, width: Int): String =
    if text.length >= width then text else text + " " * (width - text.length)

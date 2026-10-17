package scalahdl.emit

import scalahdl.core.*

/** R25 — the state-machine report, `<Module>.fsm.md`.
  *
  * A Mermaid `stateDiagram-v2` and a transition table per declared machine,
  * written beside the Verilog exactly as the R5.1 CDC report is. The graph is
  * captured at elaboration from the `goto` calls themselves, so this cannot
  * drift from the design the way a hand-drawn diagram does — which is half of
  * why R25 captures it at all.
  *
  * Guards are rendered by `VerilogEmitter`, so an edge label and the `if` that
  * guard became in the emitted always block read alike. A guard wider than
  * `InlineGuardLimit` characters would make the picture unreadable, so it is
  * replaced in the diagram by a `g1`-style reference and resolved in full in
  * the table below it. */
object FsmReport:

  /** Beyond this many characters a guard becomes a numbered reference. */
  private val InlineGuardLimit = 40

  /** True when this module declares a machine — the report exists only then,
    * unlike the CDC report, which is a per-module fact either way. */
  def applies(module: Module): Boolean = module.fsms.nonEmpty

  def render(module: Module, emitter: VerilogEmitter): String =
    val sb = new StringBuilder
    sb.append(s"# FSM report: ${module.name}\n")
    module.fsms.foreach(fsm => sb.append("\n").append(renderFsm(fsm, module, emitter)))
    sb.toString

  private def renderFsm(fsm: FsmInfo, module: Module, emitter: VerilogEmitter): String =
    val sb = new StringBuilder
    val bits = if fsm.encoding.width == 1 then "1 bit" else s"${fsm.encoding.width} bits"
    sb.append(s"## ${fsm.name} — ${fsm.encoding.name}, $bits, ")
    // One starting state reads as it always has; a machine whose power-up
    // state differs from its reset state names both.
    val start = (fsm.initCode, fsm.powerUpCode) match
      case (Some(reset), Some(powerUp)) if reset != powerUp =>
        s"initial ${short(fsm, reset)} on reset, ${short(fsm, powerUp)} at power-up"
      case _ => s"initial ${short(fsm, fsm.entryCodes.head)}"
    sb.append(s"$start\n\n")

    // Guards long enough to swamp the diagram are numbered here once and
    // referenced from both the edge label and the table.
    val longGuards = fsm.transitions.flatMap(_.guard).map(emitter.renderExpr(_, module))
      .distinct.filter(_.length > InlineGuardLimit).zipWithIndex
      .map((text, index) => text -> s"g${index + 1}").toMap

    def guardText(transition: FsmTransition): Option[String] =
      transition.guard.map(emitter.renderExpr(_, module))

    sb.append("```mermaid\nstateDiagram-v2\n")
    fsm.entryCodes.foreach(code => sb.append(s"  [*] --> ${short(fsm, code)}\n"))
    // A state with no edges of its own would otherwise be absent from the
    // picture entirely: a terminal state is drawn by naming it on its own line.
    fsm.states.filter(_.terminal).foreach(state => sb.append(s"  ${short(fsm, state.code)}\n"))
    fsm.transitions.foreach { transition =>
      val from = transition.from.map(short(fsm, _)).getOrElse("unlisted")
      val label = guardText(transition)
        .map(text => s" : ${mermaidLabel(longGuards.getOrElse(text, text))}")
        .getOrElse("")
      sb.append(s"  $from --> ${short(fsm, transition.to)}$label\n")
    }
    sb.append("```\n\n")

    sb.append("| from | to | guard | source |\n|---|---|---|---|\n")
    fsm.transitions.foreach { transition =>
      val from = transition.from.map(short(fsm, _)).getOrElse("(unlisted values)")
      val guard = guardText(transition) match
        case None => "—"
        case Some(text) =>
          val cell = tableCell(text)
          longGuards.get(text).map(reference => s"`$reference` = `$cell`").getOrElse(s"`$cell`")
      sb.append(s"| $from | ${short(fsm, transition.to)} | $guard | ${where(transition.loc)} |\n")
    }

    val terminals = fsm.terminalStates.map(state => short(fsm, state.code))
    if terminals.nonEmpty then
      sb.append(s"\nTerminal: ${terminals.mkString(", ")}\n")
    sb.toString

  /** The member's own name — the encoding's name is already in the heading, and
    * repeating it on every edge makes the diagram unreadable. */
  private def short(fsm: FsmInfo, code: BigInt): String =
    fsm.encoding.memberName(code).getOrElse(code.toString)

  /** A Mermaid transition label runs to the end of the line, so a `:` in it
    * would be read as a second label separator, and a newline cannot occur in
    * one at all. Nothing else in a rendered expression needs escaping. */
  private def mermaidLabel(text: String): String =
    text.replace(":", "&#58;").replace("\n", " ")

  /** A `|` inside a table cell ends the cell, backticks or not — and `|` is
    * bitwise-or, so it is in every guard that reads more than one condition.
    * Escaping it is what keeps the table a table. */
  private def tableCell(text: String): String = text.replace("|", "\\|")

  private def where(loc: Loc): String =
    if loc == Loc.NoLoc then "—" else s"${fileName(loc.file)}:${loc.line}"

  private def fileName(path: String): String =
    val slash = path.lastIndexOf('/')
    if slash >= 0 then path.substring(slash + 1) else path

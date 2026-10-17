package scalahdl.core

/** R25 — the captured shape of one declared state machine.
  *
  * This is *metadata*, on the same terms as R24's `EncodingInfo`: it describes
  * statements that already exist rather than adding a node that has to be
  * lowered. An `Fsm` scope opens an R22 `switch` on the state register and
  * `goto` assigns it, so validation and emission see exactly the IR the
  * equivalent hand-written switch produces. Nothing here is read by a
  * validation phase or by the Verilog backend; the elaborator's own R25 checks
  * and the `<Module>.fsm.md` report are its only consumers.
  *
  * Declaration order is preserved throughout (R20.1): states in the order their
  * arms were enlisted, transitions in the order the `goto` calls elaborated. */
final case class FsmInfo(
  /** The state register's name — the `val` R1 captured. */
  name: String,
  /** The register this machine drives. */
  signalId: SignalId,
  /** The encoding the states come from: its name, width, and members (R24). */
  encoding: EncodingInfo,
  /** The reset state's code — the register's `init`, when it has one. */
  initCode: Option[BigInt],
  /** The power-up state's code — the register's `powerUp`, when it has one. */
  powerUpCode: Option[BigInt],
  states: List[FsmState],
  transitions: List[FsmTransition],
  loc: Loc
):
  /** The declared name of a code, or the bare number for a member the encoding
    * does not name (a value only the `default` arm covers). */
  def stateName(code: BigInt): String =
    encoding.members.collectFirst { case (c, n) if c == code => n }.getOrElse(code.toString)

  def terminalStates: List[FsmState] = states.filter(_.terminal)

  /** The states the machine can start from: the reset state, then the
    * power-up state when it is another. */
  def entryCodes: List[BigInt] = (initCode.toList ++ powerUpCode.toList).distinct

/** One state: the arm `in` or `terminal` enlisted for it. */
final case class FsmState(code: BigInt, name: String, terminal: Boolean, loc: Loc)

/** One recorded `goto` or `stay`.
  *
  * `from` is `None` for a transition written in the `default` arm, which is
  * taken from every value no arm names rather than from one state.
  *
  * R25.1 — an arm naming several states records one of these per state, so a
  * shared body is several edges and the graph says nothing about how the arm
  * was spelled. They share a guard and a location, which is what makes them
  * legible in the report as one `goto` reached from more than one state.
  *
  * `guard` is the conjunction of the `when` conditions open at the `goto`,
  * negated where the scope is an `otherwise` — `None` when the transition is
  * unconditional within its arm. A guard is not the whole story of whether the
  * edge is taken: a later unconditional `goto` in the same arm overrides an
  * earlier one, exactly as the assignments do. */
final case class FsmTransition(
  from: Option[BigInt],
  to: BigInt,
  guard: Option[Expr],
  selfLoop: Boolean,
  loc: Loc
)

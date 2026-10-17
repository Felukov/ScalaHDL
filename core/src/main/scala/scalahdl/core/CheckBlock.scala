package scalahdl.core

import SignalId.*
import scalahdl.clock.ClockDomain

/** R27 — A block that exists only in simulation: the statements one `simOnly`
  * region contributed, and the clock they are evaluated on.
  *
  * It is a `DriverBlock` with the one field removed that makes a driver block a
  * driver: there is no `signalId`, because nothing here drives anything. That
  * absence is the whole construct. R12 gives every signal exactly one block and
  * the emitter reads that as "this is where the value comes from"; a block that
  * writes nothing cannot collide with a driver, cannot be a second driver, and
  * can be deleted from the output without changing a bit of the circuit — which
  * is the property `// synthesis translate_off` relies on and the reason
  * nothing inside may assign (checked at elaboration, `Code.SIM_ONLY_DRIVES`).
  *
  * `id` is the synthetic signal id the builder filed the statements under. It
  * names no signal and is never emitted; it exists because R12's projection
  * sorts statements by target, so a block needs a target to be projected into.
  * Two `simOnly` regions on one clock share a block and their statements arrive
  * in source order, which is the same rule assignments to one signal already
  * follow.
  *
  * `domain` is where the edge comes from. A check is sampled once per clock
  * edge, and R13's promise that a domain carries the reset shape applies here
  * too: the emitted block is skipped while reset is asserted, which is what a
  * SystemVerilog concurrent assertion spells `disable iff (reset)`. */
final case class CheckBlock(
  id: SignalId,
  domain: ClockDomain,
  stmts: List[Stmt],
  loc: Loc
)

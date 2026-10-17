package scalahdl.dsl

import scalahdl.clock.ClockDomain
import scalahdl.core.{Expr, Loc}
import scalahdl.elab.Module

/** R27 — A block that exists only in simulation.
  *
  * ```scala
  * simOnly {
  *   check(~slot_valid | out_valid, "slot occupied while the out stage is empty")
  * }
  * ```
  *
  * emits, at the bottom of the module:
  *
  * ```verilog
  * // synthesis translate_off
  * always @(posedge clk) begin
  *   if (rst) begin
  *     if (!(~slot_valid | out_valid)) begin
  *       $display("[%0t] Skid_w8: slot occupied while the out stage is empty", $time);
  *       $stop;
  *     end
  *   end
  * end
  * // synthesis translate_on
  * ```
  *
  * **Why a comment and not an `` `ifdef ``.** `// synthesis translate_off` is
  * honoured by Vivado, Quartus and Yosys and needs no simulator to be told
  * anything: every simulator compiles what it wraps because a simulator reads
  * it as the comment it is. An `ifdef` would need every simulation flow to
  * define the macro, and one forgotten flag is a design whose assertions are
  * silently absent — which is the failure this construct exists to make
  * unrepresentable.
  *
  * **Nothing inside may become part of the design.** Not an assignment, not a
  * `Wire`, not a `Reg`, not a memory, not an instance. This is an elaboration
  * error (`SIM_ONLY_DRIVES`) rather than a convention, and it is the whole of
  * the construct's safety: a synthesiser deletes what the pragma wraps, so a
  * design with a `simOnly` has to be bit-identical to the same design without
  * one. A convention would not do, because the divergence a stray assignment
  * introduces is between simulation and hardware — and simulation, the only
  * thing that would look, is exactly the thing that cannot see it.
  *
  * **A check is sampled on a clock edge, with reset deasserted.** The block
  * takes the domain in scope, which is R13's promise applied here: the domain
  * carries the shape, so the reset guard is not restated at every assertion.
  * That guard is what SystemVerilog's concurrent assertions spell
  * `disable iff (reset)`, and it is why an invariant that is only true of a
  * running design does not fire on the way out of reset.
  *
  * **`when` and `switch` work unchanged inside.** A check is recorded through
  * the same path an assignment is, so it takes the condition nest it was
  * written under (R12) and the arm it was written in (R22).
  *
  * **What this is not.** It is not a way to make hardware conditional on the
  * simulator; a design that must *behave* differently is a parameter, and
  * belongs where R12 can see it. And it is not a replacement for an external
  * probe: a `bind` reads a vendored module and its port through one seam and
  * so makes a swap comparable, which a check compiled into one side cannot.
  * This is for what only the module can say — an invariant stated where it is
  * maintained, in terms of signals a probe would have to reach through
  * hierarchy to name. */
inline def simOnly(inline body: => Unit)(using m: Module, cd: ClockDomain): Unit =
  m.builder.beginSimOnly(m.builder.activeDomain(cd), Loc.here)
  try body finally m.builder.endSimOnly()

/** R27 — An immediate assertion: a claim about the design, checked once per
  * clock edge while reset is deasserted, that fails loudly and is not part of
  * the circuit.
  *
  * `cond` is what must hold. `message` is fixed at elaboration and is not a
  * format string: every signal the condition names is in the waveform at the
  * time the check fires, so the message's job is to say *which claim* broke,
  * not to restate the values. Say what is wrong with the design — "slot
  * occupied while the out stage is empty" — rather than what the expression
  * computed.
  *
  * **It is named `check` rather than `assert`**, which is what R27's design
  * sketch called it, for a reason found the first time this was written down in
  * a test: `assert` is a member of `Predef` and of every test framework, and a
  * wildcard import loses to an *inherited* member. A module declared inside a
  * `munit.FunSuite` — which is how nearly every module in this repository's
  * tests is declared — would silently pick up the suite's `assert(Boolean, …)`
  * and fail to compile against an `Expr`, with an error naming neither this
  * construct nor the reason. A name that has to be qualified at the sites that
  * exercise it most is the wrong name; `check` is also what the IR calls it
  * (`Stmt.Check`, `CheckBlock`, `Module.checks`).
  *
  * **It must be written inside `simOnly`** (`CHECK_OUTSIDE_SIM_ONLY`). The
  * guard that keeps a check out of the hardware belongs to the block rather
  * than to the statement, so that one `translate_off` region covers every
  * check under it and a reader of the emitted Verilog sees one boundary
  * instead of one per line.
  *
  * **Emission is Verilog-2001** (R9), which has no `assert` and no `$error`:
  * the check is `if (!(cond)) begin $display(...); $stop; end`. `$stop` rather
  * than a report-and-continue, because a testbench that prints PASS after an
  * invariant broke is the silent failure this is here to prevent — a violation
  * ends the simulation with a non-zero status. The SystemVerilog backend R9
  * leaves open is where a native `assert` would go. */
inline def check(inline cond: Expr, inline message: String)(using m: Module): Unit =
  m.builder.addCheck(cond, message, Loc.here)

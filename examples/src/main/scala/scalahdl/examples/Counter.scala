package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

/** The Appendix C minimal end-to-end slice. A counter with explicit clock and
  * reset ports, a register, and a conditional increment — exercises R1 (name
  * capture), R3 (typed context), R10 (direct ports), R12 (literal emission),
  * R17 (IR), R20 (one file per module).
  *
  * Its test asserts what an example promises — one file, a manifest, the same
  * bytes on two runs, a lint-clean result — and not the emitted bytes, which
  * the `core` golden corpus pins. */
class Counter extends Module:
  val clk   = In(Clock())
  val rst   = In(Reset(ActiveHigh))
  val en    = In(Bool())
  val count = Out(UInt(8))

  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val cnt = Reg(UInt(8), init = 0.U(8))
  when(en) { cnt := cnt + 1.U(8) }
  count := cnt

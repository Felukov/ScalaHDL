package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

/** Exercises R1 name capture from a `val` inside a helper `def` and one inside
  * a `for` loop body — the two locations field reflection cannot reach and
  * the bet (Appendix C) is about. The emitted Verilog names them exactly as
  * written. */
class CounterGen extends Module:
  val clk    = In(Clock())
  val rst    = In(Reset(ActiveHigh))
  val en     = In(Bool())
  val max    = In(UInt(8))
  val counts = Out(UInt(8))

  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val cnt = Reg(UInt(8), init = 0.U(8))
  when(en) { cnt := cnt + 1.U(8) }

  // a val inside a helper def — its name survives to Verilog
  def threshold(w: Int): DslSignal =
    val limit = Reg(UInt(w), init = 15.U(w))
    when(cnt === limit) { cnt := 0.U(8) }
    limit

  val lim = threshold(8)

  // a val inside a for body — its name survives to Verilog
  for i <- 0 until 1 do
    val mark = Reg(UInt(8), init = 0.U(8))
    when(cnt === max) { mark := 1.U(8) }
    counts := mark

  counts := cnt

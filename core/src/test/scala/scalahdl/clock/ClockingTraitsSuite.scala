package scalahdl.clock

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.emit.VerilogEmitter

/** R13.3 — SingleClock / DualClock traits. Each test maps to a checkbox in
  * `roadmap/open/M04-scalar-types/README.md`. */
class ClockingTraitsSuite extends munit.FunSuite:

  test("class Counter extends SingleClock compiles with no hand-written clock/reset ports"):
    class Counter extends SingleClock:
      val count = Out(UInt(8))
      val cnt = Reg(UInt(8), init = 0.U(8))
      cnt := cnt + 1.U(8)
      count := cnt
    val design = (new Counter).elaborateDesign()
    assert(design.top.signals.exists(_.name == "cnt"),
      s"cnt should be in signals: ${design.top.signals.map(_.name)}")

  test("emitted ports are exactly clk and rst_n"):
    class Counter extends SingleClock:
      val count = Out(UInt(8))
      val cnt = Reg(UInt(8), init = 0.U(8))
      cnt := cnt + 1.U(8)
      count := cnt
    val design = (new Counter).elaborateDesign()
    val portNames = design.top.ports.map(_.name)
    assertEquals(portNames, List("clk", "rst_n", "count"),
      s"ports should be exactly clk, rst_n, count: $portNames")

  test("SingleClock domain is async active-low (emits negedge rst_n)"):
    class Counter extends SingleClock:
      val count = Out(UInt(8))
      val cnt = Reg(UInt(8), init = 0.U(8))
      cnt := cnt + 1.U(8)
      count := cnt
    val design = (new Counter).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Counter")
    assert(v.contains("negedge rst_n"),
      s"SingleClock should be async active-low: $v")

  test("DualClock exposes two domains usable as using clockA / using clockB"):
    class Dual extends DualClock:
      val qA = Out(UInt(8))
      val qB = Out(UInt(8))
      // DualClock has two `given ClockDomain` (clockA, clockB) — ambiguous for a
      // bare `Reg`. Resolve by making clockA the unnamed default for the A-domain
      // register, and scoping the B-domain register under `withClock(clockB)`.
      given defaultDomain: ClockDomain = clockA
      val rA = Reg(UInt(8), init = 0.U(8))
      rA := rA + 1.U(8)   // uses clockA (now the unambiguous default)
      qA := rA
      // rB should use clockB — declare and drive it under withClock(clockB)
      withClock(clockB):
        val rB = Reg(UInt(8), init = 0.U(8))
        rB := rB + 1.U(8)
        qB := rB
    val design = (new Dual).elaborateDesign()
    // Both registers should be in the IR
    assert(design.top.signals.exists(_.name == "rA"),
      s"rA should be in signals: ${design.top.signals.map(_.name)}")
    assert(design.top.signals.exists(_.name == "rB"),
      s"rB should be in signals: ${design.top.signals.map(_.name)}")
    // Check the emitted Verilog has two different sensitivity lists
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Dual")
    assert(v.contains("clkA"), s"should reference clkA: $v")
    assert(v.contains("clkB"), s"should reference clkB: $v")

package scalahdl.clock

import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}

import scalahdl.dsl.{*, given}
import scalahdl.elab.{BlackBox, Module}
import scalahdl.emit.Emission

/** R13.9 — a clock made out of one bit, once and explicitly.
  *
  * R13.1 keeps `Clock` a distinct type so a clock cannot accidentally become
  * data or data a clock, and that is worth keeping. What it also did was leave
  * a design that *derives* a clock with nowhere to go: `Wire(Clock())` declared
  * fine and nothing could be assigned to it. That is R13.7's hole one type
  * along, and this is the same door.
  *
  * Found on a computer's top level, which builds its processor's 8 MHz clock out
  * of the system's 16 MHz and two enables — a divided clock that is the whole
  * reason the CPU in that machine runs at the rate it does.
  */
class DerivedClockSuite extends munit.FunSuite:

  test("a clock may be derived from a bit and used as a domain"):
    class Top extends Module:
      val clk     = In(Clock())
      val rst     = In(Reset(ActiveHigh))
      val enRise  = In(Bool())
      val enFall  = In(Bool())
      val q       = Out(UInt(8))
      given system: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

      // One 68000 clock is one enRise/enFall pair.
      val half = Reg(Bool())
      when(enRise) { half := True }.elsewhen(enFall) { half := False }

      val coreClk = Wire(Clock())
      coreClk := half.asClock

      private val core = ClockDomain(coreClk, rst, ResetKind.Sync, ActiveHigh)
      val count = Reg(UInt(8), init = 0.U(8), domain = core)
      count := count + 1
      q := count

    val verilog = new Emission("t").emit((new Top).elaborateDesign(), "Top").files.head._2
    // the derived clock is an ordinary net driven by the divider...
    assert(verilog.contains("coreClk = half;"), verilog)
    // ...and it is what the register's block is sensitive to
    assert(verilog.contains("always @(posedge coreClk) begin"), verilog)

  test("a derived clock drives a blackbox's clock port"):
    class Vendor extends BlackBox:
      override val moduleName = "vendor_cpu"
      val clk = In(Clock()).named("clk")
      val q   = Out(Bool()).named("q")
      outputDomain(q, enclosingDomain)

    class Top extends Module:
      val clk    = In(Clock())
      val enRise = In(Bool())
      val q      = Out(Bool())
      given system: ClockDomain = ClockDomain(clk, ResetKind.None)
      val half = Reg(Bool())
      when(enRise) { half := !half }
      val coreClk = Wire(Clock())
      coreClk := half.asClock
      val cpu = inst(new Vendor)
      cpu.clk := coreClk
      q := cpu.q

    val verilog = new Emission("t").emit((new Top).elaborateDesign(), "Top").files.head._2
    assert(verilog.contains(".clk(coreClk)"), verilog)

  test("a clock is one bit, and a wider operand is a named error"):
    class Top extends Module:
      val clk = In(Clock())
      val bus = In(UInt(4))
      given ClockDomain = ClockDomain(clk, ResetKind.None)
      val bad = Wire(Clock())
      bad := bus.asClock
    val ex = intercept[CastError](new Top)
    assert(ex.getMessage.contains("one bit"), ex.getMessage)

  test("an unwidthed literal has no width to make a clock out of"):
    class Top extends Module:
      val clk = In(Clock())
      given ClockDomain = ClockDomain(clk, ResetKind.None)
      val bad = Wire(Clock())
      bad := 1.asClock
    val ex = intercept[CastError](new Top)
    assert(ex.getMessage.contains("needs the operand's width"), ex.getMessage)

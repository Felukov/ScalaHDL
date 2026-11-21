package scalahdl.aggregates

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.VerilogEmitter

/** R13 / R14.1 — a reset value on an aggregate register. The register is one
  * net per leaf, so the value reaches each leaf and no statement names the
  * record. */
class AggregateResetSuite extends munit.FunSuite:

    case class Entry(w: Int) extends Bundle:
        val full  = Bool()
        val block = UInt(w)

    test("one reset statement per leaf, and none naming the record"):
        class Top extends Module:
            val clk = In(Clock())
            val rst = In(Reset(ActiveHigh))
            given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
            val i = In(UInt(2))
            val entries = Reg(Vec(4, Entry(8)), init = 0.U(36))
            val q = Out(Bool())
            when(i === 1.U(2)) { entries(1).full := True }
            q := entries(1).full
        val design = (new Top).elaborateDesign()
        val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Top")
        assert(v.contains("entries_full[0] <= 1'b0;"), v)
        assert(v.contains("entries_full[3] <= 1'b0;"), v)
        assert(v.contains("entries_block[0] <= 8'd0;"), v)
        assert(v.contains("entries_block[3] <= 8'd0;"), v)
        assert(!v.contains("entries <="), v)

    test("each leaf takes its own bits of the reset value"):
        // Element 1 occupies [17:9]: `full` at 17, `block` at [16:9].
        // The value puts full = 1 and block = 0x5a there.
        val pattern = (1 << 17) | (0x5a << 9)
        class Top extends Module:
            val clk = In(Clock())
            val rst = In(Reset(ActiveHigh))
            given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
            val i = In(UInt(2))
            val entries = Reg(Vec(4, Entry(8)), init = pattern.U(36))
            val q = Out(Bool())
            when(i === 1.U(2)) { entries(1).full := True }
            q := entries(1).full
        val design = (new Top).elaborateDesign()
        val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Top")
        assert(v.contains("entries_full[1] <= 1'b1;"), v)
        assert(v.contains("entries_block[1] <= 8'd90;"), v)
        assert(v.contains("entries_full[0] <= 1'b0;"), v)
        assert(v.contains("entries_block[2] <= 8'd0;"), v)

package scalahdl.emit

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module

/** R12.1 (M50) — unconditional assignments to whole leaves are continuous
  * assignments, aggregates included, and what is left that could never run is
  * an error.
  *
  * The case this was written for: a payload of constants. Emitted as a
  * procedural block it reads nothing, so its sensitivity list is empty, no
  * event triggers it and the outputs stay undriven — which is what a
  * dependent project met.
  */
class ContinuousAssignSuite extends munit.FunSuite:

    final case class Ask() extends Bundle:
        val write = Bool()
        val short = Bool()

    abstract class Fix extends Module:
        val clk = In(Clock())
        val rst = In(Reset(ActiveHigh))
        given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

    private def emit(m: Module): String =
        val design = m.elaborateDesign()
        new VerilogEmitter("test").emitModule(design.top, design.top.name)

    test("a payload of constants is continuous, and no block reads nothing"):
        class Top extends Fix:
            val ask = Out(Ask())
            ask.write := False
            ask.short := True
        val v = emit(new Top)
        assert(v.contains("assign ask_write = 1'b0;"), v)
        assert(v.contains("assign ask_short = 1'b1;"), v)
        assert(!v.contains("always @(*)"), v)
        // Continuous, so the target is a wire rather than a reg (R12.1).
        assert(!v.contains("output reg ask_write"), v)

    test("constants and reads in one driver are all continuous"):
        class Top extends Fix:
            val sel = In(Bool())
            val ask = Out(Ask())
            ask.write := sel
            ask.short := True
        val v = emit(new Top)
        assert(v.contains("assign ask_write = sel;"), v)
        assert(v.contains("assign ask_short = 1'b1;"), v)
        assert(!v.contains("always @(*)"), v)

    test("a condition keeps the block procedural"):
        class Top extends Fix:
            val sel = In(Bool())
            val ask = Out(Ask())
            ask.write := False
            ask.short := False
            when(sel) { ask.short := True }
        val v = emit(new Top)
        assert(v.contains("always @(*)"), v)
        assert(!v.contains("assign ask_short"), v)

    test("a leaf written twice keeps the block procedural, because two assigns would be two drivers"):
        class Top extends Fix:
            val a = In(UInt(8))
            val q = Out(UInt(8))
            q := 0.U(8)
            q := a
        val v = emit(new Top)
        assert(v.contains("always @(*)"), v)
        assert(!v.contains("assign q ="), v)

    test("a condition on a constant is the block that could never run, and is refused"):
        // What R12.1 cannot make continuous and nothing can trigger: the
        // condition reads nothing, so neither does the block. (A part-select
        // cannot reach here — R6.7 refuses a partial write in a combinational
        // block before this.)
        class Top extends Fix:
            val q = Wire(UInt(8))
            val out = Out(UInt(8))
            q := 0.U(8)
            when(True) { q := 1.U(8) }
            out := q
        val failure = intercept[ValidationFailed]((new Top).elaborateDesign())
        val diagnostic = failure.diagnostics.find(_.code == Code.COMBINATIONAL_NEVER_RUNS)
        assert(diagnostic.isDefined, failure.diagnostics.map(_.render).mkString("\n"))
        assert(diagnostic.get.summary.contains("`q`"), diagnostic.get.summary)

package scalahdl.dsl

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.VerilogEmitter
import scalahdl.diag.{Code, ValidationFailed}

/** R34 — a runtime selection on a packed scalar vector.
  *
  * M57/R34 records this as a missing capability; the owner chose Verilog's own
  * indexed select as the lowering (R34's option 1c) — `word[i]` for a bit and
  * `word[i +: w]` for a window — because it reads back as written (R12). This
  * suite holds both:
  *
  *   - a runtime **bit** select and write, which already worked, emitted as
  *     `word[i]` with no generated mux;
  *   - a runtime **window** `word(i, w)`, emitted as `word[i +: w]`, legal to
  *     write only on a register, mirroring R6.7. */
class RuntimeBitSelectSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  private def emit(m: Module): String =
    new VerilogEmitter("0.1.1").emitModule(m.elaborateDesign().top, "Top")

  test("a runtime index reads a bit of a packed scalar as word[i], with no mux"):
    class Top extends Fix:
      val word = In(UInt(8))
      val i = In(UInt(3))
      val one = Out(Bool())
      one := word(i)
    val v = emit(new Top)
    assert(v.contains("word[i]"),
      s"a runtime scalar bit-select reads as Verilog's own indexed select: $v")
    assert(!v.toLowerCase.contains("mux"),
      s"the lowering must not invent a mux (R2/R12): $v")

  test("a runtime index writes a bit of a packed scalar register"):
    class Top extends Fix:
      val i = In(UInt(3))
      val flag = In(Bool())
      val word = Reg(UInt(8), init = 0.U(8))
      word(i) := flag
    val v = emit(new Top)
    assert(v.contains("word[i]"),
      s"a runtime scalar bit write is the same indexed select on the LHS: $v")

  test("a runtime window reads as Verilog's indexed part-select, word[i +: w]"):
    class Top extends Fix:
      val word = In(UInt(32))
      val i = In(UInt(5))
      val lane = Out(UInt(8))
      lane := word(i, 8)
    val v = emit(new Top)
    assert(v.contains("word[i +: 8]"),
      s"a runtime window reads as `word[i +: 8]` (R34/R12): $v")

  test("a runtime window writes a packed scalar register"):
    class Top extends Fix:
      val i = In(UInt(5))
      val lane = In(UInt(8))
      val word = Reg(UInt(32), init = 0.U(32))
      word(i, 8) := lane
    val v = emit(new Top)
    assert(v.contains("word[i +: 8]"),
      s"a runtime window write is the same indexed part-select on the LHS: $v")

  test("a runtime window write on a wire is refused, like a constant window (R34/R6.7)"):
    class Bad extends Fix:
      val i = In(UInt(5))
      val lane = In(UInt(8))
      val word = Wire(UInt(32))
      word(i, 8) := lane
    val ex = intercept[ValidationFailed] { new Bad().elaborateDesign() }
    assert(ex.diagnostics.exists(_.code == Code.PARTIAL_WRITE_COMBINATIONAL),
      s"expected PARTIAL_WRITE_COMBINATIONAL: ${ex.diagnostics.map(_.render)}")

  test("a window wider than its base is refused"):
    class Bad extends Fix:
      val i = In(UInt(3))
      val word = Reg(UInt(8), init = 0.U(8))
      val lane = In(UInt(16))
      word(i, 16) := lane
    val ex = intercept[ValidationFailed] { new Bad().elaborateDesign() }
    assert(ex.diagnostics.exists(_.code == Code.PARTIAL_WRITE_OUT_OF_BOUNDS),
      s"expected PARTIAL_WRITE_OUT_OF_BOUNDS: ${ex.diagnostics.map(_.render)}")

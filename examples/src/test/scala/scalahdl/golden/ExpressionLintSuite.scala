package scalahdl.golden

import java.nio.file.Files
import scala.sys.process.*
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.Emission

/** R21 — every operator the milestone adds, emitted and put through Verilator.
  *
  * Two of the defects R21 exists to fix — invalid negative-literal Verilog and
  * a dropped `$signed` wrapper — are invisible to every elaboration-level
  * check, so a lint pass is part of the implementation's evidence rather than
  * a nicety. The exhaustive per-operator matrix and the simulation oracles
  * are the oracles' job; this is the smoke check that the forms this milestone
  * emits are Verilog at all. */
class ExpressionLintSuite extends munit.FunSuite:

  private def lint(design: => Module, extraArgs: List[String] = Nil): Unit =
    assume(Seq("sh", "-c", "command -v verilator >/dev/null 2>&1").! == 0,
      "Verilator not on PATH; skipping R21's lint check")
    val m = design
    val emitted = new Emission().emit(m.elaborateDesign())
    val dir = Files.createTempDirectory("scalahdl-expr-lint")
    try
      new Emission().writeTo(emitted, dir.toString)
      emitted.files.map(_._1).foreach { file =>
        val path = dir.resolve(file).toString
        val rc = (Seq("verilator", "--lint-only", "-Wall") ++ extraArgs :+ path).!
        assertEquals(rc, 0, s"verilator --lint-only rejected $path")
      }
    finally
      val _ = Seq("rm", "-rf", dir.toString).!

  test("every R21 operator emits Verilog that passes verilator --lint-only -Wall"):
    lint(new ExprSurfaceLint)

  test("R21.1's boundary shift amounts lint at every width"):
    // 0, `w - 1`, `w`, past `w`, and an amount in a port three times wider
    // than `log2(w)`. Verilog is entitled to complain about a shift that can
    // only produce zero, so a clean `-Wall` here is the claim that the
    // emission stays inside what the language considers ordinary.
    lint(new ShiftBoundaryLint)

  test("the R6.2 narrowing exception lints, modulo the truncation it relies on"):
    // `x := (a + b).resize(8)` on an anonymous operand deliberately emits a
    // bare assignment and lets Verilog truncate at the target (R21.4). That is
    // precisely what WIDTHTRUNC reports, so the waiver is the rule, not a
    // defect: with the warning enabled this file fails, and that failure is
    // the emission working as specified.
    lint(new NarrowingLint, extraArgs = List("-Wno-WIDTHTRUNC"))

/** Every operator R21 adds, in one module, with every port used or driven so
  * `-Wall` has nothing else to say. */
class ExprSurfaceLint extends Module:
  val clk = In(Clock())
  val rst = In(Reset(ActiveHigh))
  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val a     = In(UInt(32))
  val b     = In(UInt(32))
  val sa    = In(SInt(32))
  val sb    = In(SInt(32))
  val amt   = In(UInt(5))
  val instr = In(UInt(32))
  val mask  = In(Bits(8))
  val lane  = In(Bits(8))
  val sel   = In(Bool())

  // R21.1 — shifts
  val srl = Out(UInt(32)); srl := a >> amt
  val sll = Out(UInt(32)); sll := a << amt
  val sra = Out(SInt(32)); sra := sa >>> amt
  val srlS = Out(SInt(32)); srlS := sa >> amt

  // R21.2 — ordering, unsigned and signed
  val sltu = Out(Bool()); sltu := a < b
  val slt  = Out(Bool()); slt := sa < sb
  val geu  = Out(Bool()); geu := a >= b
  val ge   = Out(Bool()); ge := sa >= sb

  // R21.3 — the full product, both signednesses
  val mulu = Out(UInt(64)); mulu := a * b
  val muls = Out(SInt(64)); muls := sa * sb

  // R21.4 — resize and casts
  val imm    = Out(SInt(32)); imm := instr(31, 20).asSInt.resize(32)
  val zimm   = Out(UInt(32)); zimm := instr(31, 20).resize(32)
  val lowbyte = Out(UInt(8)); lowbyte := a.resize(8)
  val reinterp = Out(UInt(32)); reinterp := sa.asUInt

  // R21.5 — Cat and Fill
  val jimm = Out(UInt(21))
  jimm := Cat(instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1))
  val filled = Out(UInt(24)); filled := Fill(24, instr(31))

  // R21 — bitwise on Bits, reductions, Mux with expression arms
  val masked = Out(Bits(8)); masked := (mask & lane) | (~mask)
  val differ = Out(Bool()); differ := (a ^ b).orR
  val allSet = Out(Bool()); allSet := a.andR
  val parity = Out(Bool()); parity := a.xorR
  val muxed  = Out(UInt(32)); muxed := Mux(sel, a + b, b - a)

  // R21.7 — a negative signed literal
  val decd = Out(SInt(32)); decd := sa + (-1).S(32)

  // The remaining ports exist so `-Wall` has nothing to say about unused
  // inputs: the low half of `instr`, and the clock domain itself.
  val funct = Out(UInt(12)); funct := instr(11, 0)
  val held = Out(UInt(32))
  val hold = Reg(UInt(32), init = 0.U(32))
  hold := a + b
  held := hold

/** R21.1 — the boundary shift amounts, constant and dynamic, on a 32-bit
  * operand. The amount is unsigned, of any width, and exempt from width
  * agreement, so an 8-bit amount on a 32-bit value is legal and must lint;
  * `ExpressionOracleSuite` is what says each one computes the right number. */
class ShiftBoundaryLint extends Module:
  val clk = In(Clock())
  val rst = In(Reset(ActiveHigh))
  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val a = In(UInt(32))
  val s = In(SInt(32))
  val wide = In(UInt(8))          // three times wider than log2(32)

  // Constant amounts: nothing, w - 1, w, and past w.
  val at0 = Out(UInt(32)); at0 := a >> 0.U
  val at31 = Out(UInt(32)); at31 := a >> 31.U
  val at32 = Out(UInt(32)); at32 := a >> 32.U
  val at33 = Out(UInt(32)); at33 := a >> 33.U
  val left31 = Out(UInt(32)); left31 := a << 31.U
  val left32 = Out(UInt(32)); left32 := a << 32.U

  // The same boundaries reached dynamically, and an arithmetic shift by an
  // amount that cannot fit in log2(w) bits.
  val dyn = Out(UInt(32)); dyn := a >> wide
  val dynArith = Out(SInt(32)); dynArith := s >>> wide

  // A register so the clock domain's ports are used and `-Wall` stays on topic.
  val held = Out(UInt(32))
  val hold = Reg(UInt(32), init = 0.U(32))
  hold := a
  held := hold

/** The one form that relies on Verilog's native truncation at the target. */
class NarrowingLint extends Module:
  val clk = In(Clock())
  val rst = In(Reset(ActiveHigh))
  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val a = In(UInt(16))
  val b = In(UInt(16))
  val q = Out(UInt(8))
  q := (a + b).resize(8)

  // A register so the clock domain's ports are used and `-Wall` stays on
  // topic — the only warning this module is meant to provoke is WIDTHTRUNC.
  val held = Out(UInt(16))
  val hold = Reg(UInt(16), init = 0.U(16))
  hold := a
  held := hold

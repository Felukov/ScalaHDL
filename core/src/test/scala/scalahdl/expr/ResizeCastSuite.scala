package scalahdl.expr

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic}
import scalahdl.emit.VerilogEmitter

/** R21.4 / R21.5 — one `resize`, casts that preserve representation, and the
  * `Cat`/`Fill` constructors.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M13-cpu-expressions/R21.4-resize-casts.md` and
  * `R21.5-concat-replicate.md`. */
class ResizeCastSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def emit(body: => Module): String =
    val m = body
    val design = m.elaborateDesign()
    new VerilogEmitter("0.1.2").emitModule(design.top, m.moduleName)

  def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  // ---- R21.4: resize ---------------------------------------------------

  test("widening a UInt fills with zeros; widening an SInt replicates the sign"):
    class Good extends Fix:
      val u = In(UInt(8)); val s = In(SInt(8))
      val uw = Out(UInt(32)); val sw = Out(SInt(32))
      uw := u.resize(32)
      sw := s.resize(32)
    val v = emit(new Good)
    assert(v.contains("{{24{1'b0}}, u}"), s"zero fill for UInt: $v")
    assert(v.contains("{{24{s[7]}}, s}"), s"sign fill for SInt: $v")

  test("the two spellings are not interchangeable: sign- vs zero-extension"):
    class SignThenWiden extends Fix:
      val b = In(Bits(8))
      val q = Out(SInt(32))
      q := b.asSInt.resize(32)            // reinterpret, then sign-extend
    class WidenThenSign extends Fix:
      val b = In(Bits(8))
      val q = Out(SInt(32))
      q := b.resize(32).asSInt            // zero-extend, then reinterpret
    val signed = emit(new SignThenWiden)
    val zero = emit(new WidenThenSign)
    assert(signed.contains("{{24{b[7]}}, b}"),
      s"asSInt.resize sign-extends off the operand's top bit: $signed")
    assert(zero.contains("{{24{1'b0}}, b}"),
      s"resize.asSInt zero-extends first: $zero")
    assert(signed != zero,
      "the two spellings must not produce the same hardware")

  test("narrowing keeps the low w bits"):
    class Good extends Fix:
      val a = In(UInt(32))
      val q = Out(UInt(8))
      q := a.resize(8)
    assert(emit(new Good).contains("a[7:0]"), emit(new Good))

  test("narrowing a slice composes into one absolute selection"):
    class Good extends Fix:
      val instr = In(UInt(32))
      val q = Out(UInt(4))
      q := instr(31, 20).resize(4)      // the low 4 bits of bits 31:20
    assert(emit(new Good).contains("instr[23:20]"),
      s"Verilog has no part-select of a part-select: ${emit(new Good)}")

  test("a one-bit slice emits a bit-select, not a degenerate part-select"):
    class Good extends Fix:
      val opcode = In(UInt(16))
      val q = Out(Bool())
      q := opcode(10, 10) === 1.U(1)
    val v = emit(new Good)
    assert(v.contains("opcode[10]"), s"a one-bit window is a bit-select: $v")
    assert(!v.contains("opcode[10:10]"),
      s"the degenerate part-select must not survive emission: $v")

  test("R12 — slice, index, and sign-extension spell the same bit alike"):
    // `sig(10, 10)` is a one-bit slice and `sig(10)` a bit-select; R6's Bool
    // exception makes both assignable to a Bool, and R12 makes both read alike.
    class ViaSlice extends Fix:
      val opcode = In(UInt(16)); val q = Out(Bool())
      q := opcode(10, 10)
    class ViaIndex extends Fix:
      val opcode = In(UInt(16)); val q = Out(Bool())
      q := opcode(10)
    assert(emit(new ViaSlice).contains("q = opcode[10];"),
      s"one selection, one spelling: ${emit(new ViaSlice)}")
    assert(emit(new ViaIndex).contains("q = opcode[10];"),
      s"one selection, one spelling: ${emit(new ViaIndex)}")

    // R21.4's sign fill picks the MSB, which for a one-bit-wide window is the
    // same selection reached through the other printer.
    class ViaSignExtend extends Fix:
      val s = In(SInt(8)); val q = Out(SInt(16))
      q := s.resize(16)
    assert(emit(new ViaSignExtend).contains("s[7]"),
      s"the sign bit is a bit-select too: ${emit(new ViaSignExtend)}")

  test("resize to the operand's own width is the identity and emits nothing"):
    class Good extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      q := a.resize(8)
    val v = emit(new Good)
    assert(v.contains("q = a;"), s"an identity resize emits the operand: $v")

  test("R6.2 exception: a narrowing resize of an anonymous whole RHS is bare"):
    class Good extends Fix:
      val a = In(UInt(16)); val b = In(UInt(16))
      val q = Out(UInt(8))
      q := (a + b).resize(8)     // legal on an anonymous operand
    val v = emit(new Good)
    assert(v.contains("q = (a + b);"),
      s"Verilog truncates natively at the target, so no selection is emitted: $v")

  test("narrowing an anonymous expression elsewhere errors, and a val fixes it"):
    class Bad extends Fix:
      val a = In(UInt(16)); val b = In(UInt(16)); val c = In(UInt(8))
      val q = Out(UInt(8))
      q := (a + b).resize(8) & c       // not the whole RHS any more
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("requires a named signal"), msg)

    class Good extends Fix:
      val a = In(UInt(16)); val b = In(UInt(16)); val c = In(UInt(8))
      val q = Out(UInt(8))
      val sum = Wire(UInt(16))
      sum := a + b
      q := sum.resize(8) & c
    assert(emit(new Good).contains("sum[7:0]"), emit(new Good))

  test("sign-extending an anonymous expression errors (it emits a bit-select)"):
    class Bad extends Fix:
      val a = In(SInt(8)); val b = In(SInt(8))
      val q = Out(SInt(16))
      q := (a + b).resize(16)
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("requires a named signal"), msg)
    assert(msg.contains("sign bit"), s"the message must say why: $msg")

  test("the immediate-extraction path: sign extension of a slice, no Wire"):
    class Good extends Fix:
      val instr = In(UInt(32))
      val imm = Out(SInt(32))
      imm := instr(31, 20).asSInt.resize(32)
    val v = emit(new Good)
    assert(v.contains("{{20{instr[31]}}, instr[31:20]}"),
      s"a slice is bit-addressable, so this needs no intermediate: $v")

  // ---- R21.4: casts ----------------------------------------------------

  test("a cast emits its operand unchanged"):
    class Good extends Fix:
      val s = In(SInt(8))
      val q = Out(UInt(8))
      q := s.asUInt              // -1.S(8).asUInt is 255: no bit moves
    val v = emit(new Good)
    assert(v.contains("q = s;"), s"reinterpretation costs nothing: $v")

  test("round trip: a.asSInt.asUInt is bit-identical to a and emits `a`"):
    class Good extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      q := a.asSInt.asUInt
    assert(emit(new Good).contains("q = a;"), emit(new Good))

  test("a negative literal cast to UInt keeps its bits and its emission"):
    class Good extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      q := a + (-1).S(8).asUInt
    val v = emit(new Good)
    assert(v.contains("-8'sd1"), s"the literal keeps its own emission: $v")

  test("casts apply to anonymous trees, not only to named signals"):
    class Good extends Fix:
      val a = In(UInt(8)); val b = In(UInt(8))
      val q = Out(SInt(8))
      q := (a + b).asSInt
    assert(emit(new Good).contains("q = (a + b);"), emit(new Good))

  test("`.asBits` on an aggregate is its packed word (R7.2)"):
    // R21.4's cast and R7.2's packing are one operation, because a bundle *is*
    // a vector of bits: the same bits, the same width, no signedness. What the
    // emitted Verilog has to spell out is R07.1's flattening, not a conversion
    // the caller performed.
    case class Pair(w: Int) extends Bundle:
      val a = UInt(w)
      val b = UInt(w)
    class Good extends Fix:
      val p = In(Pair(4))
      val q = Out(Bits(8))
      q := p.asBits
    assert(emit(new Good).contains("{p_a, p_b}"), emit(new Good))

  // ---- R21.5: Cat and Fill ---------------------------------------------

  test("Cat(a, b) of two UInt(4) is UInt(8) with a in the high bits"):
    class Good extends Fix:
      val a = In(UInt(4)); val b = In(UInt(4))
      val q = Out(UInt(8))
      q := Cat(a, b)
    val v = emit(new Good)
    assert(v.contains("{a, b}"), s"first argument in the MSBs (R7.2 order): $v")

  test("the J-immediate reassembly is exactly 21 bits and one concatenation"):
    class Good extends Fix:
      val instr = In(UInt(32))
      val imm = Out(UInt(21))
      imm := Cat(instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1))
    val v = emit(new Good)
    assert(v.contains("{instr[31], instr[19:12], instr[20], instr[30:21], 1'b0}"),
      s"one Verilog concatenation: $v")
    assertEquals(v.count(_ == '{'), 1, s"exactly one concatenation: $v")

  test("Cat(a, 1.U) errors on the unwidthed part; Cat(a, 1.U(1)) compiles"):
    class Bad extends Fix:
      val a = In(UInt(4))
      val q = Out(UInt(5))
      q := Cat(a, 1.U)
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("Cat part 2 of 2"), s"the message names the part: $msg")
    assert(msg.contains("1.U(1)") || msg.contains("width"), msg)

    class Good extends Fix:
      val a = In(UInt(4))
      val q = Out(UInt(5))
      q := Cat(a, 1.U(1))
    assert(emit(new Good).contains("{a, 1'b1}"), emit(new Good))

  test("a Cat is UInt regardless of its parts' kinds"):
    class Good extends Fix:
      val s = In(SInt(4)); val b = In(Bits(4))
      val q = Out(UInt(8))
      q := Cat(s, b)
    assert(emit(new Good).contains("{s, b}"), emit(new Good))

  test("Fill(24, sign) is UInt(24) and emits {24{sign}}"):
    class Good extends Fix:
      val sign = In(Bool())
      val q = Out(UInt(24))
      q := Fill(24, sign)
    assert(emit(new Good).contains("{24{sign}}"), emit(new Good))

  test("Fill(0, x) errors"):
    val err = intercept[IllegalArgumentException]:
      class Bad extends Fix:
        val a = In(UInt(4))
        val q = Out(UInt(4))
        q := Fill(0, a)
      (new Bad).elaborateDesign()
    assert(err.getMessage.contains("at least one repetition"), err.getMessage)

  test("sign extension by Fill + Cat agrees with resize"):
    class Good extends Fix:
      val instr = In(UInt(32))
      val a = Out(UInt(32)); val b = Out(SInt(32))
      a := Cat(Fill(20, instr(31)), instr(31, 20))
      b := instr(31, 20).asSInt.resize(32)
    val v = emit(new Good)
    assert(v.contains("{{20{instr[31]}}, instr[31:20]}"),
      s"both spellings reach the same Verilog form: $v")

  test("a Cat used as a multiply operand keeps its own summed width"):
    class Good extends Fix:
      val a = In(UInt(4)); val b = In(UInt(4)); val c = In(UInt(4))
      val q = Out(UInt(12))
      q := Cat(a, b) * c          // UInt(8) * UInt(4) → UInt(12)
    val v = emit(new Good)
    assert(v.contains("{{4{1'b0}}, {a, b}}"),
      s"the concatenation is widened as an operand, not re-sized by context: $v")

package scalahdl.expr

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic, Code}
import scalahdl.emit.VerilogEmitter

/** R21.7 — literals participate without widening or truncating anything: the
  * width each new operand position gives them, the two's-complement range
  * check at both ends, and the emission of a negative signed literal.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M13-cpu-expressions/R21.7-literal-participation.md`. */
class LiteralParticipationSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def emit(body: => Module): String =
    val m = body
    val design = m.elaborateDesign()
    new VerilogEmitter("0.1.1").emitModule(design.top, m.moduleName)

  def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  // ---- width through the whole surface (Appendix D finding 4) ----------

  test("instr(31, 20) + 1.U resolves the literal to 12 bits"):
    class Good extends Fix:
      val instr = In(UInt(32))
      val q = Out(UInt(12))
      q := instr(31, 20) + 1.U
    val v = emit(new Good)
    assert(v.contains("12'd1"), s"the slice's width is knowable: $v")

  test("a literal's sibling may be a cast, a resize, a shift, or a product"):
    class Good extends Fix:
      val a = In(UInt(8)); val n = In(UInt(3))
      val s = In(SInt(8))
      val viaCast = Out(SInt(8)); val viaResize = Out(UInt(16))
      val viaShift = Out(UInt(8)); val viaProduct = Out(UInt(16))
      viaCast := s.asSInt + 1.S
      viaResize := a.resize(16) + 1.U
      viaShift := (a >> n) + 1.U
      viaProduct := (a * a) + 1.U
    val v = emit(new Good)
    assert(v.contains("8'sd1"), s"the cast's width reaches the literal: $v")
    assert(v.contains("16'd1"), s"the resize's and product's width do too: $v")
    assert(v.contains("8'd1"), s"the shift keeps the left operand's width: $v")

  // ---- the shift amount is the one value-determined position -----------

  test("a shift amount literal takes its own minimal width"):
    class Good extends Fix:
      val a = In(UInt(32))
      val q = Out(UInt(32)); val r = Out(UInt(32))
      q := a >> 3.U          // 3 needs 2 bits
      r := a << 0.U          // 0 still needs 1
    val v = emit(new Good)
    assert(v.contains("(a >> 2'd3)"), s"the amount takes width 2: $v")
    assert(v.contains("(a << 1'b0)"), s"max(1, bitLength): $v")

  // ---- Mux arms (R21.7's Mux row) --------------------------------------

  test("Mux(sel, a, 0.U) resolves the literal arm from the other arm"):
    class Good extends Fix:
      val sel = In(Bool()); val a = In(UInt(8))
      val q = Out(UInt(8))
      q := Mux(sel, a, 0.U)
    assert(emit(new Good).contains("8'd0"), emit(new Good))

  test("Mux(sel, 1.U, 0.U) errors: two literal arms have no context"):
    class Bad extends Fix:
      val sel = In(Bool())
      val q = Out(UInt(8))
      q := Mux(sel, 1.U, 0.U)
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("no inferrable width"), msg)

  // ---- the signed range, at both ends, on both paths --------------------

  test("inferred width: -8 and 7 fit in SInt(4); -9 and 8 do not"):
    def signedLit(v: Int): Either[String, String] =
      class Design extends Fix:
        val a = In(SInt(4))
        val q = Out(SInt(4))
        q := a + v.S
      try Right(emit(new Design))
      catch case e: ValidationFailed => Left(e.diagnostics.map(_.render).mkString)
    assert(signedLit(-8).isRight, s"-8 is representable in 4-bit two's complement: ${signedLit(-8)}")
    assert(signedLit(7).isRight, s"7 is representable: ${signedLit(7)}")
    assert(signedLit(-9).isLeft, "-9 is not representable in 4 bits")
    assert(signedLit(8).isLeft, "8 is not representable in 4 bits")

  test("explicit width: the same range is checked (it used to be skipped)"):
    def signedLit(v: Int): Either[String, String] =
      class Design extends Fix:
        val a = In(SInt(4))
        val q = Out(SInt(4))
        q := a + v.S(4)
      try Right(emit(new Design))
      catch case e: ValidationFailed => Left(e.diagnostics.map(_.render).mkString)
    assert(signedLit(-8).isRight, s"-8.S(4) is legal: ${signedLit(-8)}")
    assert(signedLit(7).isRight, s"7.S(4) is legal: ${signedLit(7)}")
    assert(signedLit(-9).isLeft, "-9.S(4) must be rejected")
    assert(signedLit(8).isLeft, "8.S(4) must be rejected")

  test("an over-range signed literal reports a signed fix, not an unsigned one"):
    class Bad extends Fix:
      val a = In(SInt(4))
      val q = Out(SInt(4))
      q := a + (-9).S
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("-9"), s"names the value: $msg")
    assert(msg.contains("requires 5 bits"), s"names the required width: $msg")
    assert(msg.contains("provides 4"), s"names the available width: $msg")
    assert(msg.contains(".S("), s"the fix's signedness must match the literal's: $msg")
    assert(!msg.contains(".U("), s"a signed literal must not be fixed with .U: $msg")

  test("an unsigned literal that is negative is rejected as such"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      q := a + (-1).U
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("negative but written as unsigned"), msg)
    assert(msg.contains(".S"), s"the fix names the signed spelling: $msg")

  // ---- negative literal emission (Appendix D finding 6) ----------------

  test("a + (-1).S(32) emits -32'sd1, never 32'sd-1"):
    class Good extends Fix:
      val a = In(SInt(32))
      val q = Out(SInt(32))
      q := a + (-1).S(32)
    val v = emit(new Good)
    assert(v.contains("-32'sd1"), s"the sign sits outside the sized constant: $v")
    assert(!v.contains("'sd-"), s"`32'sd-1` is not Verilog at all: $v")

  test("the most negative value of a width still emits legally"):
    class Good extends Fix:
      val a = In(SInt(4))
      val q = Out(SInt(4))
      q := a + (-8).S(4)
    val v = emit(new Good)
    assert(v.contains("-4'sd8"), v)
    assert(!v.contains("'sd-"), v)

  // ---- the R6.1 guard, as R6.3 narrowed it -----------------------------

  test("x := 1.U takes the target's width (R6.3)"):
    // Was the assignment-boundary guard's headline case until R6.3 observed
    // that a literal which *is* the right-hand side has no sibling to widen.
    class Good extends Fix:
      val q = Out(UInt(8))
      q := 1.U
    val v = emit(new Good)
    assert(v.contains("8'd1"), v)

  test("a nested literal still takes its sibling's width, not the target's"):
    // The guard's actual job, and the reason R6.3 is a leaf rule: `a` is 8 bits
    // and `q` is 16, so inferring from the target would silently widen `a`.
    // Resolving from the sibling instead leaves the real width error loud.
    class Bad extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(16))
      q := a + 1.U
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("width-8 expression"), msg)
    assert(msg.contains("width 16"), msg)

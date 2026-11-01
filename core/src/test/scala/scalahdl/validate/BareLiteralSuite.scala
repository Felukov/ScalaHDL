package scalahdl.validate

import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Diagnostic, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.VerilogEmitter

/** R06.4 — a bare `Int` takes both its width and its kind from context.
  * R06.5 — an assignment admits no implicit kind conversion.
  *
  * The two are one milestone because each is what makes the other safe. R6.4
  * lets a literal land in a kind it was not written with, which would be a way
  * to smuggle a conversion past an assignment that only checked widths; R6.5
  * closes that, and in return R6.4 makes the strictness affordable, since the
  * fix for `flag := 0.U` on a `Bool` is `flag := 0` rather than a cast.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M18-bare-literals/README.md`. */
class BareLiteralSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  private def emit(body: => Module): String =
    val m = body
    new VerilogEmitter("0.1.1").emitModule(m.elaborateDesign().top, m.moduleName)

  private def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  // ---- R6.4: the three positions, and the kinds ------------------------

  test("a bare Int works where a suffixed literal does"):
    class Good extends Fix:
      val funct3 = In(UInt(3))
      val w = Wire(UInt(5))
      val hit = Out(Bool())
      val counter = Reg(UInt(8), init = 0)
      w := 2
      hit := funct3 === 5
      counter := counter + 1
    val v = emit(new Good)
    assert(v.contains("5'd2"), v)
    assert(v.contains("3'd5"), v)
    assert(v.contains("8'd1"), v)
    assert(v.contains("8'd0"), v)

  test("a bare Int case label takes the selector's width"):
    class Good extends Fix:
      val sel = In(UInt(3))
      val q = Out(UInt(5))
      q := 0
      switch(sel) {
        is(2) { q := 1 }
        default { q := 3 }
      }
    val v = emit(new Good)
    assert(v.contains("3'd2:"), v)

  test("a bare Int takes SInt from context, including a negative value"):
    class Good extends Fix:
      val s = In(SInt(8))
      val sum = Out(SInt(8))
      val down = Out(SInt(8))
      val level = Reg(SInt(8), init = 0)
      sum := s + 1
      down := s + (-1)
      level := s
    val v = emit(new Good)
    assert(v.contains("8'sd1"), v)
    assert(v.contains("-8'sd1"), s"a negative signed literal keeps its sign outside: $v")

  test("a bare Int reaches Bits and Bool, which have no literal suffix"):
    // The gap R6.4 closes as a side effect: `mask === 5.U` and `flag === 1.U`
    // are kind mismatches, and there is no `.Bits`/`.B` to write instead, so
    // before R6.4 these comparisons had no working spelling at all.
    class Good extends Fix:
      val mask = In(Bits(4))
      val flag = In(Bool())
      val maskHit = Out(Bool())
      val flagHit = Out(Bool())
      val raw = Out(Bits(4))
      maskHit := mask === 5
      flagHit := flag === 1
      raw := 3
    val v = emit(new Good)
    assert(v.contains("4'd5"), v)
    assert(v.contains("4'd3"), v)

  test("a bare Int in a position with no context is an error naming both halves"):
    class Bad extends Fix:
      val a = In(UInt(4))
      val q = Out(UInt(5))
      q := Cat(a, 1)
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("no width"), msg)

  test("an out-of-range bare Int is rejected against the kind it landed in"):
    class Bad extends Fix:
      val q = Out(UInt(5))
      q := 300
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("300"), msg)
    assert(msg.contains("9"), msg)

  test("a negative bare Int against an unsigned context is still rejected"):
    // The one thing taking a kind from context must never paper over.
    class Bad extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      q := a + (-1)
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("negative"), msg)

  test("a suffix still fixes the kind, and is not overridden by context"):
    class Bad extends Fix:
      val s = In(SInt(8))
      val q = Out(SInt(8))
      q := s + 1.U
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("cannot + SInt and UInt"), msg)

  // ---- R6.5: assignment kind agreement ---------------------------------

  test("assigning an SInt to a UInt target is an error"):
    // The hole R6.5 closes: this was accepted silently, for signals as much as
    // for literals, while `q === s` had always been rejected.
    class Bad extends Fix:
      val s = In(SInt(8))
      val q = Out(UInt(8))
      q := s
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("cannot assign SInt"), msg)
    assert(msg.contains("no implicit kind conversion"), msg)
    assert(msg.contains(".asUInt"), s"the fix names an API that exists: $msg")

  test("assigning a UInt to a Bits target is an error"):
    class Bad extends Fix:
      val a = In(UInt(4))
      val q = Out(Bits(4))
      q := a
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("cannot assign UInt"), msg)
    assert(msg.contains(".asBits"), msg)

  test("a width mismatch keeps priority over a kind mismatch"):
    // One mistake, one diagnostic: the kind check runs only once the widths
    // agree, so a wrong-width SInt does not report both.
    class Bad extends Fix:
      val s = In(SInt(4))
      val q = Out(UInt(8))
      q := s
    val errs = expectFailure(new Bad)
    assertEquals(errs.length, 1)
    assert(errs.head.render.contains("no implicit extension"), errs.head.render)

  test("Bool is interchangeable with a one-bit unsigned value"):
    // The deliberate exception. A bit-select is typed Bool and a one-bit port
    // is naturally UInt(1); the two emit identically, so requiring a cast
    // between them would be ceremony protecting nothing.
    class Good extends Fix:
      val a = In(UInt(8))
      val lsb = Out(UInt(1))
      val flag = Out(Bool())
      lsb := a(0)
      flag := 0.U
    val v = emit(new Good)
    assert(v.contains("a["), v)

  test("a one-bit SInt is not interchangeable with Bool"):
    // Outside the exception on purpose: a one-bit SInt holds 0 or -1, which is
    // a different value, not a different spelling of the same one.
    class Bad extends Fix:
      val s = In(SInt(1))
      val q = Out(Bool())
      q := s
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("cannot assign SInt"), msg)

  // ---- emission is unchanged -------------------------------------------

  test("a bare Int emits what the suffixed literal emitted"):
    class Suffixed extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      val r = Reg(UInt(8), init = 0.U(8))
      q := a + 1.U(8)
      r := 7.U(8)
    class Bare extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      val r = Reg(UInt(8), init = 0)
      q := a + 1
      r := 7
    def body(v: String, name: String): String =
      v.linesIterator.drop(3).mkString("\n").replace(name, "M")
    assertEquals(body(emit(new Bare), "Bare"), body(emit(new Suffixed), "Suffixed"))

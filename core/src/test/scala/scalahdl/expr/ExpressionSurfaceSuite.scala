package scalahdl.expr

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic, Code}
import scalahdl.emit.VerilogEmitter

/** R21 — the complete expression surface: operand-kind completeness, the
  * arithmetic/bitwise split that makes `Bits` maskable, `Mux` arms that accept
  * expressions, and the type admission table.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M13-cpu-expressions/R21-expression-surface.md`. */
class ExpressionSurfaceSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def emit(body: => Module): String =
    val m = body
    val design = m.elaborateDesign()
    new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, m.moduleName)

  def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  // ---- operand-kind completeness --------------------------------------

  test("every operator takes signal, expression, or literal on either side"):
    class Good extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val mask = In(UInt(8))
      val q = Out(UInt(8))
      // signal ⊕ signal, expression ⊕ signal, signal ⊕ expression,
      // expression ⊕ expression, and a literal on either side.
      val s0 = a - b
      val s1 = (a - b) & mask
      val s2 = mask & (a - b)
      val s3 = (a | b) ^ (a & mask)
      val s4 = 1.U + a
      val s5 = a + 1.U
      q := (s1 | s2) ^ (s3 & (s4 - s5))
    val v = emit(new Good)
    assert(v.contains("q = "), s"expected a driver for q: $v")

  test("`mask & (a - b)` and `(a - b) & mask` emit the same operands"):
    class Left extends Fix:
      val a = In(UInt(8)); val b = In(UInt(8)); val mask = In(UInt(8))
      val q = Out(UInt(8))
      q := mask & (a - b)
    class Right extends Fix:
      val a = In(UInt(8)); val b = In(UInt(8)); val mask = In(UInt(8))
      val q = Out(UInt(8))
      q := (a - b) & mask
    assert(emit(new Left).contains("(mask & (a - b))"), emit(new Left))
    assert(emit(new Right).contains("((a - b) & mask)"), emit(new Right))

  test("R2 holds: no operator declares a wire (wire count == named-val count)"):
    class Good extends Fix:
      val a = In(UInt(8)); val b = In(UInt(8)); val mask = In(UInt(8))
      val q = Out(UInt(8))
      q := ((a - b) & mask) ^ (a + b)
    val design = (new Good).elaborateDesign()
    // clk, rst, a, b, mask, q — six named vals, six signals, no more.
    assertEquals(design.top.signals.length, 6,
      s"anonymous trees must not declare: ${design.top.signals.map(_.name)}")

  // ---- Mux arms are expressions (Appendix D finding 3) -----------------

  test("Mux(sel, a + b, b - c) elaborates and emits one ternary"):
    class Good extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8)); val b = In(UInt(8)); val c = In(UInt(8))
      val q = Out(UInt(8))
      q := Mux(sel, a + b, b - c)
    val v = emit(new Good)
    assert(v.contains("(sel ? (a + b) : (b - c))"), s"one ternary expected: $v")
    assertEquals(v.split('?').length - 1, 1, s"exactly one ternary: $v")

  test("Mux arms must agree in type"):
    class Bad extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8)); val b = In(SInt(8))
      val q = Out(UInt(8))
      q := Mux(sel, a, b)
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("Mux arms have different types"), msg)
    assert(msg.contains("UInt") && msg.contains("SInt"), msg)

  // ---- the arithmetic / bitwise split (Appendix D finding 2) -----------

  test("bitwise ops on Bits(4) elaborate; a byte-lane mask is a Bits value"):
    class Good extends Fix:
      val a = In(Bits(4)); val b = In(Bits(4))
      val q = Out(Bits(4))
      q := (a & b) | (~a ^ b)
    val v = emit(new Good)
    assert(v.contains("&") && v.contains("|") && v.contains("~"), v)

  test("`+` on Bits(4) still errors, and gives the Bits reason"):
    class Bad extends Fix:
      val a = In(Bits(4)); val b = In(Bits(4))
      val q = Out(Bits(4))
      q := a + b
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("not defined on `Bits`"), msg)
    assert(!msg.contains("clock"),
      s"the stale clock reason must be gone: $msg")
    assert(msg.contains("no signedness to interpret"), msg)

  test("`a < b` on Bits errors naming .asUInt as the fix"):
    class Bad extends Fix:
      val a = In(Bits(4)); val b = In(Bits(4))
      val q = Out(Bool())
      q := a < b
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("`<` is not defined on `Bits`"), msg)
    assert(msg.contains(".asUInt"), s"the fix must name an API that exists: $msg")

  test("ordering on Bool errors — a condition is not a number"):
    class Bad extends Fix:
      val a = In(Bool()); val b = In(Bool())
      val q = Out(Bool())
      q := a < b
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("not defined on `Bool`"), msg)

  // ---- reductions and unary operators ----------------------------------

  test("orR / andR / xorR yield Bool and emit (|a), (&a), (^a)"):
    class Good extends Fix:
      val a = In(UInt(8))
      val any = Out(Bool()); val all = Out(Bool()); val par = Out(Bool())
      any := a.orR
      all := a.andR
      par := a.xorR
    val v = emit(new Good)
    assert(v.contains("(|a)"), s"orR emits (|a): $v")
    assert(v.contains("(&a)"), s"andR emits (&a): $v")
    assert(v.contains("(^a)"), s"xorR emits (^a): $v")

  test("a reduction of an anonymous expression is admissible"):
    class Good extends Fix:
      val a = In(UInt(8)); val b = In(UInt(8))
      val q = Out(Bool())
      q := (a ^ b).orR                 // "are these two different?"
    assert(emit(new Good).contains("(|(a ^ b))"), emit(new Good))

  test("`!` is Bool-only; `~` complements any bit-typed value"):
    class Good extends Fix:
      val flag = In(Bool())
      val a = In(UInt(8))
      val q = Out(Bool()); val n = Out(UInt(8))
      q := !flag
      n := ~(a + 1.U)
    val v = emit(new Good)
    assert(v.contains("(!flag)"), v)
    assert(v.contains("(~(a + 8'd1))"), v)

  test("`!` on a UInt errors and points at `===`"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val q = Out(Bool())
      q := !a
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("`!` is not defined on `UInt`"), msg)

  // ---- types that stay excluded ----------------------------------------

  test("Clock and Reset admit none of the surface"):
    class Bad extends Fix:
      val other = In(Clock())
      val q = Out(Bool())
      q := clk === other
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("not defined on `Clock`"), msg)
    assert(msg.contains("a clock used as data is a type error"), s"the Clock reason: $msg")

  test("GrayCode admits only its preserving operations"):
    class Bad extends Fix:
      val g = In(GrayCode(4))
      val h = In(GrayCode(4))
      val q = Out(UInt(4))
      q := g + h
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("not defined on `GrayCode`"), msg)
    assert(msg.contains("admits only its preserving operations"), msg)

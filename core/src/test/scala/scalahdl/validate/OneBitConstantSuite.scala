package scalahdl.validate

import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Diagnostic, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.VerilogEmitter

/** R06.6 — `True` and `False` name the one-bit constants.
  *
  * Nothing here is newly *writable*: R6.4 already let `flag := 1` take both
  * width and kind from the target, and that is the point. What the requirement
  * adds is that a control signal's constant can say *asserted* rather than
  * `1`, at no cost to the emitted Verilog — so the suite's obligations are to
  * pin the spelling in every position a literal reaches, to pin the width at
  * one bit where the target would otherwise have supplied it, and to prove the
  * output did not move.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M22-one-bit-constants/README.md`. */
class OneBitConstantSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  private def emit(body: => Module): String =
    val m = body
    new VerilogEmitter("0.1.2").emitModule(m.elaborateDesign().top, m.moduleName)

  private def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  // ---- the positions a one-bit constant reaches ------------------------

  test("True and False assign, initialise, and compare against a Bool"):
    class Good extends Fix:
      val start = In(Bool())
      val busy = Out(Bool())
      val idle = Out(Bool())
      val frame = Reg(Bool(), init = False)
      busy := True
      idle := False
      frame := start === True
    val v = emit(new Good)
    assert(v.contains("1'b1"), v)
    assert(v.contains("1'b0"), v)

  test("True and False are case labels on a Bool selector"):
    class Good extends Fix:
      val mode = In(Bool())
      val q = Out(UInt(4))
      switch(mode) {
        is(False) { q := 1 }
        is(True) { q := 2 }
      }
    val v = emit(new Good)
    assert(v.contains("1'b0:"), v)
    assert(v.contains("1'b1:"), v)

  test("True and False are Mux arms and bitwise operands"):
    class Good extends Fix:
      val sel = In(Bool())
      val en = In(Bool())
      val q = Out(Bool())
      val gated = Out(Bool())
      q := Mux(sel, True, False)
      gated := en & True
    val v = emit(new Good)
    assert(v.contains("?"), v)

  test("True and False carry their own width into a Cat part"):
    // The one place a bare `Int` cannot go (R21.5: a part whose width was
    // inferred would silently change the width of the whole). `False` carries
    // a width, so it needs no `.U(1)` to be a padding bit.
    class Good extends Fix:
      val a = In(UInt(4))
      val q = Out(UInt(5))
      q := Cat(a, False)
    val v = emit(new Good)
    assert(v.contains("1'b0"), v)

  test("a one-bit unsigned target takes True and False through R6.5's exception"):
    // A one-bit port is naturally declared `UInt(1)` and holds the same value
    // a `Bool` does. The exception is what was written for this, so the
    // spelling reaches it without a cast.
    class Good extends Fix:
      val flag = Out(UInt(1))
      val carry = Reg(UInt(1), init = False)
      flag := True
      carry := False
    val v = emit(new Good)
    assert(v.contains("1'b1"), v)

  // ---- the width is fixed at one bit ------------------------------------

  test("a wider target does not widen True"):
    // The decision the requirement turns on. With an inferred width this would
    // take eight bits from the target and emit `8'b1`, and R6.5's Bool
    // exception would pass the kind, so nothing else would catch it.
    class Bad extends Fix:
      val word = Out(UInt(8))
      word := True
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("no implicit extension"), msg)
    assert(msg.contains("8"), msg)

  test("comparing a multi-bit signal against True is the kind error it always was"):
    // `word === someBool` has always been rejected, and R6.6 does not carve
    // out an operator exception: the fix is to declare the signal `Bool()`.
    class Bad extends Fix:
      val word = In(UInt(8))
      val hit = Out(Bool())
      hit := word === True
    val errs = expectFailure(new Bad).map(_.render).mkString
    assert(errs.contains("UInt") && errs.contains("Bool"), errs)

  test("an SInt target admits neither, at any width"):
    // Outside R6.5's exception on purpose: a one-bit `SInt` holds 0 or -1.
    class Bad extends Fix:
      val s = Out(SInt(1))
      s := True
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("cannot assign Bool"), msg)

  // ---- diagnostics name the spelling ------------------------------------

  test("a suffixed literal against a Bool target is told the constant's name"):
    // `0.S` rather than `0.U`, because a one-bit unsigned literal is inside
    // R6.5's exception and is simply accepted. It is also the only value that
    // reaches here: `1.S` needs two bits, so the width check reports first and
    // keeps its priority.
    class Bad extends Fix:
      val flag = Out(Bool())
      flag := 0.S
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("name the one-bit constant"), msg)
    assert(msg.contains(":= False"), msg)

  // ---- emission is unchanged --------------------------------------------

  test("True and False emit what the sized literals emitted"):
    // The R24-shaped claim: this is a surface feature, so a design converted
    // to it is byte-identical to the same design written with `0.U(1)`.
    class Sized extends Fix:
      val start = In(Bool())
      val busy = Out(Bool())
      val flag = Out(UInt(1))
      val frame = Reg(Bool(), init = 0.U(1))
      busy := 1.U(1)
      flag := 0.U(1)
      frame := start
    class Named extends Fix:
      val start = In(Bool())
      val busy = Out(Bool())
      val flag = Out(UInt(1))
      val frame = Reg(Bool(), init = False)
      busy := True
      flag := False
      frame := start
    def body(v: String, name: String): String =
      v.linesIterator.drop(3).mkString("\n").replace(name, "M")
    assertEquals(body(emit(new Named), "Named"), body(emit(new Sized), "Sized"))

  test("True and False emit what the bare Ints emitted"):
    class Bare extends Fix:
      val q = Out(Bool())
      val r = Reg(Bool(), init = 0)
      q := 1
      r := 0
    class Named extends Fix:
      val q = Out(Bool())
      val r = Reg(Bool(), init = False)
      q := True
      r := False
    def body(v: String, name: String): String =
      v.linesIterator.drop(3).mkString("\n").replace(name, "M")
    assertEquals(body(emit(new Named), "Named"), body(emit(new Bare), "Bare"))

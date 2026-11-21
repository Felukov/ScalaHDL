package scalahdl.validate

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic, Code}
import scalahdl.emit.VerilogEmitter

/** R06.1 — Literal width from context. Each test maps to a checkbox in
  * `roadmap/open/M04-scalar-types/README.md`. */
class LiteralWidthSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def expectFailure(body: => Module): List[Diagnostic] =
    val ex = intercept[ValidationFailed] { body.elaborateDesign() }
    ex.diagnostics

  // ---- inference from BinOp context ----------------------------------

  test("wbin + 1 with wbin: UInt(8) resolves 1 to width 8 and emits 8'd1"):
    class Good extends Fix:
      val wbin = In(UInt(8))
      val q = Out(UInt(8))
      q := wbin + 1.U       // 1.U has no width; inherits wbin's width 8
    val design = (new Good).elaborateDesign()
    val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Good")
    assert(v.contains("8'd1"),
      s"literal should be resolved to width 8: $v")

  test("x := a + 1 resolves 1 to a's width, not x's (the guard)"):
    class Good extends Fix:
      val a = In(UInt(8))
      val x = Out(UInt(16))
      x := a + 1.U           // 1.U inherits a's width 8, not x's 16
    // The literal resolves to a's width (8); the expression is width 8,
    // the target is 16 → WIDTH_MISMATCH at the assignment, which is the
    // desired loudness (the guard: the literal does NOT inherit x's width).
    val errs = expectFailure(new Good)
    assert(errs.exists(_.code == Code.WIDTH_MISMATCH),
      s"should error: width-8 expr assigned to width-16 target: ${errs.map(_.render)}")
    val msg = errs.map(_.render).mkString
    assert(msg.contains("width-8"), s"should name the expression width 8: $msg")
    assert(msg.contains("width 16"), s"should name the target width 16: $msg")

  test("255.U in a UInt(4) context errors naming required 8 / available 4"):
    class Bad extends Fix:
      val a = In(UInt(4))
      val q = Out(UInt(4))
      q := a + 255.U         // 255 needs 8 bits, but a provides only 4
    val errs = expectFailure(new Bad)
    val msg = errs.map(_.render).mkString
    assert(msg.contains("255"), s"should name the literal value: $msg")
    assert(msg.contains("8"), s"should name required width 8: $msg")
    assert(msg.contains("4"), s"should name available width 4: $msg")

  test("1 + 2 (two literals, no context) errors: literal width uninferrable"):
    class Bad extends Fix:
      val q = Out(UInt(8))
      q := 1.U + 2.U         // both None-width, no context
    val errs = expectFailure(new Bad)
    val msg = errs.map(_.render).mkString
    assert(msg.contains("no inferrable width"),
      s"should say 'no inferrable width': $msg")

  // ---- explicit-width literal overrides inference --------------------

  test("1.U(8) explicit width overrides inference"):
    class Good extends Fix:
      val a = In(UInt(16))
      val q = Out(UInt(16))
      q := a + 1.U(8)        // explicit width 8 — but a is 16, so this should mismatch
    val errs = expectFailure(new Good)
    assert(errs.exists(_.code == Code.WIDTH_MISMATCH),
      s"explicit 8-bit literal with 16-bit operand should mismatch: ${errs.map(_.render)}")

  test("matching explicit width elaborates cleanly"):
    class Good extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      q := a + 1.U(8)        // both 8 — clean
    val design = (new Good).elaborateDesign()
    assert(design.top.signals.exists(_.name == "q"))

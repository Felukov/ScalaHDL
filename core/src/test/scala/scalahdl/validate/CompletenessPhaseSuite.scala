package scalahdl.validate

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic, Code}

/** R11 / R11.1 — combinational completeness and the register exemption.
  * Each test maps to a checkbox in
  * `roadmap/open/M02-validation-core/README.md`. */
class CompletenessPhaseSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def expectFailure(body: => Module): List[Diagnostic] =
    val ex = intercept[ValidationFailed] { body.elaborateDesign() }
    ex.diagnostics

  test("a `when`-only assignment with no default and no `otherwise` errors"):
    class Bad extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val y = Out(UInt(8))
      when(sel) { y := a }
    val errs = expectFailure(new Bad)
    val msg = errs.map(_.render).mkString
    assert(errs.exists(_.code == Code.COMBINATIONAL_INCOMPLETE),
      s"expected COMBINATIONAL_INCOMPLETE: $msg")
    assert(msg.contains("`y`"), s"should name the signal: $msg")

  test("adding a default before the `when` silences the error"):
    class Good extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val y = Out(UInt(8))
      y := 0.U(8)             // default before the conditional
      when(sel) { y := a }
    val design = (new Good).elaborateDesign()   // must not throw
    assert(design.top.signals.exists(_.name == "y"))

  test("an `otherwise` branch covering the path silences the error"):
    class Good extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val b = In(UInt(8))
      val y = Out(UInt(8))
      when(sel) { y := a }
      .otherwise { y := b }
    val design = (new Good).elaborateDesign()   // must not throw
    assert(design.top.signals.exists(_.name == "y"))

  test("error names both the declaration line and the condition"):
    class Bad extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val y = Out(UInt(8))
      when(sel) { y := a }
    val errs = expectFailure(new Bad)
    val d = errs.find(_.code == Code.COMBINATIONAL_INCOMPLETE).getOrElse(
      fail(s"expected COMBINATIONAL_INCOMPLETE: ${errs.map(_.render)}"))
    // The driven signal's declaration line is the primary location.
    assert(d.primaryLoc != Loc.NoLoc, "primary loc (declaration line) must be set")
    // The uncovered condition names the `when`'s condition signal.
    assert(d.summary.contains("sel"), s"summary should name the condition: ${d.summary}")

  // ---- R11.1: registers are exempt ------------------------------------

  test("a Reg driven on some-but-not-all paths produces no completeness error"):
    class Good extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val r = Reg(UInt(8), init = 0.U(8))
      when(sel) { r := a }     // partial: unassigned when !sel → hold, no error
    val design = (new Good).elaborateDesign()   // must not throw
    assert(design.top.signals.exists(_.name == "r"))

  test("same `when` over a wire errors but over a Reg does not (the teaching point)"):
    // Wire case: errors.
    class WireCase extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val y = Out(UInt(8))
      when(sel) { y := a }
    val wireErrs = expectFailure(new WireCase)
    assert(wireErrs.exists(_.code == Code.COMBINATIONAL_INCOMPLETE),
      s"wire case should error: ${wireErrs.map(_.render)}")
    // Register case: same shape, no error.
    class RegCase extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val r = Reg(UInt(8), init = 0.U(8))
      when(sel) { r := a }
    val design = (new RegCase).elaborateDesign()   // must not throw
    assert(design.top.signals.exists(_.name == "r"))

  test("emitted always block omits unassigned paths (no spurious `x <= x`"):
    class Good extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val r = Reg(UInt(8), init = 0.U(8))
      when(sel) { r := a }
    val design = (new Good).elaborateDesign()
    import scalahdl.emit.VerilogEmitter
    val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Good")
    // The register block should NOT contain `r <= r;` (self-hold injected).
    assert(!v.contains("r <= r;"),
      s"emitter must not inject a self-assignment for hold: $v")
    // It SHOULD contain the conditional assignment under sel.
    assert(v.contains("if (sel)"), s"should emit the when branch: $v")
end CompletenessPhaseSuite

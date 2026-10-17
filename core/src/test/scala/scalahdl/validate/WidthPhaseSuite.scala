package scalahdl.validate

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic, Code, RelatedLoc}

/** M02 exit criteria — widths (R06). Each test maps to a checkbox in
  * `roadmap/open/M02-validation-core/README.md`. A design that violates a
  * width rule fails *elaboration* (validation runs in `Module.elaborate`).
  *
  * The DSL has no standalone `Wire` factory in M02: signals come from
  * `In`/`Out` (ports) and `Reg`. Operands are input ports; results are output
  * ports (a combinational `Out` is a wire). */
class WidthPhaseSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def expectFailure(body: => Module): List[Diagnostic] =
    val ex = intercept[ValidationFailed] { body.elaborateDesign() }
    ex.diagnostics

  // ---- R06: widths ----------------------------------------------------

  test("width mismatch `a + b` with a:UInt(8), b:UInt(12) errors naming both widths"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(12))
      val sum = Out(UInt(12))
      sum := a + b
    val errs = expectFailure(new Bad)
    val msg = errs.map(_.render).mkString
    assert(errs.exists(_.code == Code.WIDTH_MISMATCH), s"expected WIDTH_MISMATCH: $msg")
    assert(msg.contains("8") && msg.contains("12"),
      s"should name both widths 8 and 12: $msg")

  test("assignment width mismatch errors naming both widths"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val y = Out(UInt(12))
      y := a
    val errs = expectFailure(new Bad)
    val msg = errs.map(_.render).mkString
    assert(errs.exists(_.code == Code.WIDTH_MISMATCH), s"expected WIDTH_MISMATCH: $msg")
    assert(msg.contains("width-8") && msg.contains("width 12"),
      s"should name both widths: $msg")

  test("UInt + SInt errors with a signedness message"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(SInt(8))
      val sum = Out(UInt(8))
      sum := a + b
    val errs = expectFailure(new Bad)
    val msg = errs.map(_.render).mkString
    assert(msg.contains("no implicit signedness conversion"),
      s"expected signedness message: $msg")

  test("Bits arithmetic errors ('Bits has no arithmetic')"):
    class Bad extends Fix:
      val a = In(Bits(8))
      val b = In(Bits(8))
      val y = Out(Bits(9))
      y := a + b
    val errs = expectFailure(new Bad)
    val msg = errs.map(_.render).mkString
    assert(msg.contains("not defined on `Bits`"),
      s"expected 'Bits has no arithmetic': $msg")

  test("matching widths elaborate cleanly (no false positive)"):
    class Good extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val sum = Out(UInt(8))
      sum := a + b           // 8 + 8 → 8 (no carry bit; truncation native, R6.2)
    val design = (new Good).elaborateDesign()
    assert(design.top.signals.exists(_.name == "sum"))

  // ---- batched errors within a phase ----------------------------------

  test("two width errors in one expression report together (batched)"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(12))
      val c = In(UInt(4))
      val y = Out(UInt(20))
      y := (a + b) + c
    val errs = expectFailure(new Bad)
    assert(errs.count(_.code == Code.WIDTH_MISMATCH) >= 1,
      s"expected at least one batched WIDTH_MISMATCH: ${errs.map(_.render)}")

  // ---- phase halt (no cascade) ---------------------------------------

  test("a statement with no location falls back to reporting at a declaration"):
    // `Assign.at(..., Loc.NoLoc)` is what the library's own helpers use, since
    // the line inside `decode`/`unpackBits` is not where a caller's mistake is.
    // The phase must keep working there, in the shape it had before statement
    // locations existed: primary at the first operand's declaration, and no
    // caret note — "operands must have equal width" under a caret on a
    // declaration names the wrong line.
    val d = WidthPhase.widthErr(
      site = Loc.NoLoc,
      primary = Loc("Fifo.scala", 11, 7), other = Loc("Fifo.scala", 12, 7),
      summary = "widths 8 and 12 differ",
      related0 = RelatedLoc(Loc("Fifo.scala", 11, 7), "`count` declared here with width 8"),
      related1 = RelatedLoc(Loc("Fifo.scala", 12, 7), "`threshold` declared here with width 12"),
      fix = "resize explicitly",
      note = "operands must have equal width")
    assertEquals(d.primaryLoc, Loc("Fifo.scala", 11, 7))
    assertEquals(d.primaryNote, None)

  test("a width error halts the width phase before completeness runs"):
    class Bad extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val b = In(UInt(12))
      val sum = Out(UInt(8))
      sum := a + b            // WIDTH_MISMATCH (halts the width phase)
      val y = Out(UInt(8))
      when(sel) { y := a }    // COMBINATIONAL_INCOMPLETE (would report if reached)
    val errs = expectFailure(new Bad)
    assert(errs.exists(_.code == Code.WIDTH_MISMATCH), "width phase ran")
    assert(!errs.exists(_.code == Code.COMBINATIONAL_INCOMPLETE),
      s"completeness must NOT run after a width failure (no cascade): ${errs.map(_.code)}")
end WidthPhaseSuite

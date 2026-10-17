package scalahdl.switchcase

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}

/** R22/R19 — every way a `switch` can be wrong, and what it says when it is.
  *
  * Each test names a stable `Code` rather than matching prose, and the ones
  * about *where* the mistake is assert two locations: R22 reports a duplicate
  * at the later occurrence and relates the first, which is only useful if both
  * are real source positions. `switch`, `is`, and `default` capture their own
  * call sites for exactly this. */
class SwitchDiagnosticsSuite extends munit.FunSuite:
  import SwitchFixtures.*

  private def failure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  private def diagnostic(code: Code)(body: => Module): Diagnostic =
    val errs = failure(body)
    errs.find(_.code == code).getOrElse(
      fail(s"expected $code, got ${errs.map(d => d.code -> d.summary)}"))

  /** The two locations R22 promises: the later occurrence, and the first. */
  private def assertLaterThanRelated(d: Diagnostic): Unit =
    assert(d.primaryLoc != Loc.NoLoc, s"primary location must be a real position: $d")
    val related = d.related.map(_.loc).filter(_.file == d.primaryLoc.file)
    assert(related.exists(_.line < d.primaryLoc.line),
      s"expected a related location above line ${d.primaryLoc.line}, got ${d.related.map(_.loc)}")

  // ---- duplicates --------------------------------------------------------

  test("a value repeated inside one alias list is a duplicate"):
    val d = diagnostic(Code.SWITCH_DUPLICATE_VALUE) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          is(1.U(2), 1.U(2)) { y := 1.U(8) }
        }
    }
    assert(d.summary.contains("1.U(2)"), d.summary)

  test("a value repeated across alternatives is a duplicate, reported at the later one"):
    val d = diagnostic(Code.SWITCH_DUPLICATE_VALUE) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          is(1.U(2)) { y := 1.U(8) }
          is(2.U(2)) { y := 2.U(8) }
          is(1.U(2)) { y := 3.U(8) }
        }
    }
    assertLaterThanRelated(d)

  test("duplicates produced by two unrelated iterators are still caught"):
    // Neither producer can see the other's values; only a check that runs
    // after the whole body has elaborated finds this (R22).
    val d = diagnostic(Code.SWITCH_DUPLICATE_VALUE) {
      new Fix:
        val sel = In(UInt(3))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          for v <- List(0, 1, 2) do is(v.U(3)) { y := 1.U(8) }
          List(2, 3).foreach { v => is(v.U(3)) { y := 2.U(8) } }
        }
    }
    assertLaterThanRelated(d)

  test("canonical identity is the bit pattern, not the spelling"):
    val d = diagnostic(Code.SWITCH_DUPLICATE_VALUE) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          is(3.U(2)) { y := 1.U(8) }
          is(1.U(2) | 2.U(2)) { y := 2.U(8) }
        }
    }
    assert(d.summary.contains("3.U(2)"), d.summary)

  test("a second `default` names both defaults"):
    val d = diagnostic(Code.SWITCH_DUPLICATE_DEFAULT) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        switch(sel) {
          is(0.U(2)) { y := 1.U(8) }
          default { y := 2.U(8) }
          default { y := 3.U(8) }
        }
    }
    assertLaterThanRelated(d)

  // ---- values ------------------------------------------------------------

  test("`is()` with no values is an error"):
    val d = diagnostic(Code.SWITCH_EMPTY_ARM) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          is() { y := 1.U(8) }
        }
    }
    assert(d.fix.exists(_.contains("is(")), d.fix.toString)

  test("a case value that is not an elaboration-time constant is an error"):
    val d = diagnostic(Code.SWITCH_VALUE_NOT_CONSTANT) {
      new Fix:
        val sel = In(UInt(2))
        val other = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          is(other) { y := 1.U(8) }
        }
    }
    assert(d.fix.exists(_.contains("when")), s"the fix names the construct that does work: ${d.fix}")

  test("a case value of the wrong width is an error naming both types"):
    val d = diagnostic(Code.SWITCH_VALUE_TYPE_MISMATCH) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          is(0.U(3)) { y := 1.U(8) }
        }
    }
    assert(d.summary.contains("UInt(3)") && d.summary.contains("UInt(2)"), d.summary)

  test("a case value of the wrong kind is an error, with no implicit conversion"):
    val d = diagnostic(Code.SWITCH_VALUE_TYPE_MISMATCH) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          is(0.S(2)) { y := 1.U(8) }
        }
    }
    assert(d.summary.contains("SInt(2)"), d.summary)

  test("an unsized literal value takes the selector's width"):
    // The other half of the same rule: R6.1 resolution runs first, so `is(1.U)`
    // is the selector's width rather than a mismatch.
    class Inferred extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) {
        is(1.U) { y := 1.U(8) }
      }
    val sw = onlySwitch(new Inferred, "y")
    assertEquals(sw.cases.head.values, List(Expr.uint(BigInt(1), 2)))

  test("a selector with no values to select on is an error"):
    val d = diagnostic(Code.SWITCH_SELECTOR_TYPE) {
      new Fix:
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(clk) {
          is(0.U(1)) { y := 1.U(8) }
        }
    }
    assert(d.summary.contains("Clock"), d.summary)

  // ---- context -----------------------------------------------------------

  test("`is` outside a switch is an error that says where it is legal"):
    val d = diagnostic(Code.SWITCH_ARM_CONTEXT) {
      new Fix:
        val y = Out(UInt(8))
        is(0.U(2)) { y := 1.U(8) }
    }
    assert(d.fix.exists(_.contains("switch(")), d.fix.toString)

  test("`default` outside a switch is an error"):
    val d = diagnostic(Code.SWITCH_ARM_CONTEXT) {
      new Fix:
        val y = Out(UInt(8))
        default { y := 1.U(8) }
    }
    assert(d.summary.contains("`default`"), d.summary)

  test("an alternative inside another alternative is an error"):
    val d = diagnostic(Code.SWITCH_ARM_CONTEXT) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          is(0.U(2)) {
            is(1.U(2)) { y := 1.U(8) }
          }
        }
    }
    assertLaterThanRelated(d)

  test("a statement in a switch body but outside every alternative is an error"):
    val d = diagnostic(Code.SWITCH_ARM_CONTEXT) {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        y := 0.U(8)
        switch(sel) {
          y := 1.U(8)
          is(0.U(2)) { y := 2.U(8) }
        }
    }
    assert(d.summary.contains("`is(...)` or `default`"), d.summary)

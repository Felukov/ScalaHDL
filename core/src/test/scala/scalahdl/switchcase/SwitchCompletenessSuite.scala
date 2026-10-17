package scalahdl.switchcase

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}

/** R11/R11.1/R22 — combinational completeness through a switch.
  *
  * The rule has two halves and both are tested here: a switch covers the
  * selector's values when it has a `default` or names every value, and it
  * covers *this signal* when every reachable alternative assigns it. A wire
  * needs both; a register needs neither, because an uncovered value means
  * hold (R11.1) — the asymmetry M02 made the teaching point. */
class SwitchCompletenessSuite extends munit.FunSuite:
  import SwitchFixtures.*

  private def incomplete(body: => Module): Diagnostic =
    val errs = intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics
    errs.find(_.code == Code.COMBINATIONAL_INCOMPLETE).getOrElse(
      fail(s"expected COMBINATIONAL_INCOMPLETE, got ${errs.map(d => d.code -> d.summary)}"))

  test("a `default` that assigns the wire completes it"):
    class Good extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      switch(sel) {
        is(0.U(2)) { y := 1.U(8) }
        is(1.U(2)) { y := 2.U(8) }
        default { y := 0.U(8) }
      }
    val _ = (new Good).elaborateDesign()

  test("naming every value the selector can take completes it without a default"):
    class Good extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      switch(sel) {
        is(0.U(2)) { y := 1.U(8) }
        is(1.U(2), 2.U(2)) { y := 2.U(8) }
        is(3.U(2)) { y := 3.U(8) }
      }
    val _ = (new Good).elaborateDesign()

  test("full coverage is exact: one value short is incomplete"):
    val d = incomplete {
      new Fix:
        val sel = In(UInt(2))
        val y = Out(UInt(8))
        switch(sel) {
          is(0.U(2)) { y := 1.U(8) }
          is(1.U(2)) { y := 2.U(8) }
          is(2.U(2)) { y := 3.U(8) }
        }
    }
    assert(d.summary.contains("the values no alternative names"), d.summary)
    assert(d.fix.exists(_.contains("default")), d.fix.toString)

  test("full coverage does not enumerate: a 32-bit selector is simply not covered"):
    // The check is a count against 2^w, so a wide selector costs one
    // comparison rather than four billion insertions (R22).
    val d = incomplete {
      new Fix:
        val sel = In(UInt(32))
        val y = Out(UInt(8))
        switch(sel) {
          is(0.U(32)) { y := 1.U(8) }
          is(1.U(32)) { y := 2.U(8) }
        }
    }
    assert(d.summary.contains("the values no alternative names"), d.summary)

  test("an assignment before the switch covers what the switch does not"):
    class Good extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) {
        is(0.U(2)) { y := 1.U(8) }
      }
    val _ = (new Good).elaborateDesign()

  test("an alternative that does not assign the wire is named in the diagnostic"):
    val d = incomplete {
      new Fix:
        val sel = In(UInt(2))
        val other = Out(UInt(8))
        val y = Out(UInt(8))
        other := 0.U(8)
        switch(sel) {
          is(0.U(2)) { y := 1.U(8) }
          is(1.U(2)) { other := 1.U(8) }
          default { y := 0.U(8) }
        }
    }
    assert(d.summary.contains("is(1)"), s"the uncovered alternative is named: ${d.summary}")
    assert(d.related.exists(_.note.contains("does not assign `y`")), d.related.toString)

  test("a `default` that does not assign the wire leaves it uncovered"):
    val d = incomplete {
      new Fix:
        val sel = In(UInt(2))
        val other = Out(UInt(8))
        val y = Out(UInt(8))
        other := 0.U(8)
        switch(sel) {
          is(0.U(2)) { y := 1.U(8) }
          is(1.U(2)) { y := 2.U(8) }
          is(2.U(2)) { y := 3.U(8) }
          is(3.U(2)) { y := 4.U(8) }
          default { other := 1.U(8) }
        }
    }
    assert(d.summary.contains("`default`"), d.summary)

  test("a nested `when` inside an alternative must still cover the wire"):
    val d = incomplete {
      new Fix:
        val sel = In(UInt(1))
        val go = In(Bool())
        val y = Out(UInt(8))
        switch(sel) {
          is(0.U(1)) { when(go) { y := 1.U(8) } }
          is(1.U(1)) { y := 2.U(8) }
        }
    }
    assert(d.summary.contains("is(0)"), d.summary)

  test("a default inside the alternative satisfies the nested `when`"):
    class Good extends Fix:
      val sel = In(UInt(1))
      val go = In(Bool())
      val y = Out(UInt(8))
      switch(sel) {
        is(0.U(1)) {
          y := 0.U(8)
          when(go) { y := 1.U(8) }
        }
        is(1.U(1)) { y := 2.U(8) }
      }
    val _ = (new Good).elaborateDesign()

  test("a register holds on the values no alternative names (R11.1)"):
    class Good extends Fix:
      val sel = In(UInt(2))
      val state = Reg(UInt(8), init = 0.U(8))
      val y = Out(UInt(8))
      switch(sel) {
        is(0.U(2)) { state := 1.U(8) }
      }
      y := state
    val _ = (new Good).elaborateDesign()

  test("an aggregate leaf is covered per leaf through a switch"):
    class Good extends Fix:
      val sel = In(UInt(1))
      val out = Out(Vec(2, UInt(8)))
      switch(sel) {
        is(0.U(1)) { out(0) := 1.U(8); out(1) := 2.U(8) }
        is(1.U(1)) { out(0) := 3.U(8); out(1) := 4.U(8) }
      }
    val _ = (new Good).elaborateDesign()

    val d = incomplete {
      new Fix:
        val sel = In(UInt(1))
        val out = Out(Vec(2, UInt(8)))
        switch(sel) {
          is(0.U(1)) { out(0) := 1.U(8); out(1) := 2.U(8) }
          is(1.U(1)) { out(0) := 3.U(8) }
        }
    }
    assert(d.summary.contains("out(1)"), s"the uncovered leaf is named: ${d.summary}")

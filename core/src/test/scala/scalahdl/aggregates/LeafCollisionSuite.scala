package scalahdl.aggregates

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Code}

/** R07.1 — leaf-name collisions. Two declarations producing the same
  * flattened leaf name is a `NAME_COLLISION` under R1. The realistic case:
  * `val a_b = ...` and `val a = Bundle { val b = ... }` both emit `a_b`. */
class LeafCollisionSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def expectFailure(body: => Module): List[scalahdl.diag.Diagnostic] =
    val ex = intercept[ValidationFailed] { body.elaborateDesign() }
    ex.diagnostics

  test("a bundle field flattening to an existing name collides (R07.1)"):
    // `val a_b` and `val a = Bundle { val b }` both emit `a_b`.
    class Bad extends Fix:
      val a_b = In(UInt(8))
      val a = In(B(8))     // B has field `b` → leaf `a_b`
      val q = Out(UInt(8))
      q := a_b
    val errs = expectFailure(new Bad)
    assert(errs.exists(_.code == Code.NAME_COLLISION),
      s"expected NAME_COLLISION: ${errs.map(_.render)}")
    val d = errs.find(_.code == Code.NAME_COLLISION).get
    assert(d.summary.contains("a_b"), s"should name the colliding leaf: ${d.summary}")

  // A small bundle with a single field `b`, so `In(B(8))` flattens to `a_b`.
  case class B(w: Int) extends Bundle:
    val b = UInt(w)

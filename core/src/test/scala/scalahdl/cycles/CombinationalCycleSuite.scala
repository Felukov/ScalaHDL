package scalahdl.cycles

import scalahdl.dsl.{*, given}
import scalahdl.elab.{BlackBox, Module}
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}

/** R28 — a combinational cycle is an elaboration error.
  *
  * The two halves are equally load-bearing: every ring here must be refused,
  * and every shape that only *looks* like one must still elaborate. A check
  * that refuses a correct design is worse than no check at all. */
class CombinationalCycleSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def refused(body: => Module): Diagnostic =
    val failure = intercept[ValidationFailed] { body.elaborateDesign() }
    failure.diagnostics.find(_.code == Code.COMBINATIONAL_CYCLE).getOrElse(
      fail(s"expected COMBINATIONAL_CYCLE, got ${failure.diagnostics.map(_.render)}"))

  // ------------------------------------------------------------- the rings

  test("two wires that need each other are refused, and the ring is named"):
    class Ring extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val a = Wire(UInt(8))
      val b = Wire(UInt(8))
      a := b + d
      b := a
      q := a
    val error = refused(new Ring)
    assert(error.summary.contains("a -> b -> a") || error.summary.contains("b -> a -> b"),
      s"the ring itself belongs in the message: ${error.summary}")
    assert(error.primaryLoc != Loc.NoLoc, "the closing assignment is the primary location")
    assert(error.related.exists(_.note.contains("needs")),
      s"every hop is a related location: ${error.related.map(_.note)}")
    assert(error.fix.exists(_.contains("register")), "the fix names the way out")

  test("a wire computed from itself is the one-node ring"):
    class SelfFeeding extends Fix:
      val q = Out(UInt(8))
      val y = Wire(UInt(8))
      y := y + 1.U(8)
      q := y
    val error = refused(new SelfFeeding)
    assert(error.summary.contains("`y`"), s"names the wire: ${error.summary}")

  test("the ring is found through a `when` condition, not only through a value"):
    class GuardRing extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val a = Wire(UInt(8))
      val hot = Wire(Bool())
      a := d
      when(hot) { a := d + 1.U(8) }
      hot := a === 3.U(8)
      q := a
    refused(new GuardRing)

  test("the ring is found through a `switch` selector"):
    class SwitchRing extends Fix:
      val d = In(UInt(2))
      val q = Out(UInt(8))
      val sel = Wire(UInt(2))
      val a = Wire(UInt(8))
      switch(sel):
        is(0.U(2)) { a := 1.U(8) }
        default    { a := d.resize(8) }
      sel := a(1, 0)
      q := a
    refused(new SwitchRing)

  // ------------------------------------------------------- what is not one

  test("a register closing the loop is a counter, not a ring"):
    class Counter extends Fix:
      val q = Out(UInt(8))
      val y = Reg(UInt(8), init = 0.U(8))
      y := y + 1.U(8)
      q := y
    (new Counter).elaborateDesign()

  test("a bundle wire whose one field is computed from another is not a ring"):
    case class Pair() extends Bundle:
      val lo = UInt(8)
      val hi = UInt(8)
    class Fields extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val r = Wire(Pair())
      r.lo := d
      r.hi := r.lo + 1.U(8)
      q := r.hi
    (new Fields).elaborateDesign()

  test("a chain across the elements of a Vec wire is not a ring"):
    class Chain extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val v = Wire(Vec(4, UInt(8)))
      v(0) := d
      for i <- 1 until 4 do v(i) := v(i - 1) + 1.U(8)
      q := v(3)
    (new Chain).elaborateDesign()

  // ---------------------------------------------------------- through a child

  class Passthrough extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    val a   = In(UInt(8))
    val y   = Out(UInt(8))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
    y := a + 1.U(8)

  class Registered extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    val a   = In(UInt(8))
    val y   = Out(UInt(8))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
    val held = Reg(UInt(8), init = 0.U(8))
    held := a
    y := held

  test("an instance is edges: a ring closed through a combinational child is found"):
    class Ring extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val w = Wire(UInt(8))
      val u = inst(new Passthrough)
      u.a := w
      w := u.y + d
      q := w
    val error = refused(new Ring)
    assert(error.summary.contains("u_y") || error.summary.contains("`w`"),
      s"the ring names the crossing: ${error.summary}")

  test("the same shape through a registered child is not a ring"):
    class Fine extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val w = Wire(UInt(8))
      val u = inst(new Registered)
      u.a := w
      w := u.y + d
      q := w
    (new Fine).elaborateDesign()

  // ------------------------------------------------------ through a blackbox

  class Gate extends BlackBox:
    override val moduleName = "vendor_gate"
    val clk = In(Clock())
    val a   = In(UInt(8))
    val y   = Out(UInt(8))
    val here = enclosingDomain
    outputDomain(y, here)

  class TransparentGate extends BlackBox:
    override val moduleName = "vendor_gate_comb"
    val clk = In(Clock())
    val a   = In(UInt(8))
    val y   = Out(UInt(8))
    val here = enclosingDomain
    outputDomain(y, here)
    combPath(a, y)

  test("a blackbox that declares no path cannot close a ring, and says so"):
    class Top extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val w = Wire(UInt(8))
      val u = inst(new Gate)
      u.clk := clk
      u.a := w
      w := u.y + d
      q := w
    val design = (new Top).elaborateDesign()
    val graph = CombGraph.build(design.top)
    assert(graph.assumptions.exists(_.contains("declares no combinational path")),
      s"the assumption must be recorded: ${graph.assumptions}")

  test("a blackbox that declares one closes the ring it really closes"):
    class Top extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val w = Wire(UInt(8))
      val u = inst(new TransparentGate)
      u.clk := clk
      u.a := w
      w := u.y + d
      q := w
    refused(new Top)

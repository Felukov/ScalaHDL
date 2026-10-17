package scalahdl.switchcase

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh

/** R22 — the DSL surface and the first-class IR it produces.
  *
  * The claim under test is that a `switch` *stays* a switch: it is not
  * elaborated into nested conditionals, its alternatives keep the order the
  * elaborator observed them in, and a Scala producer that calls `is` is
  * indistinguishable from the same calls written out by hand. */
class SwitchSurfaceSuite extends munit.FunSuite:
  import SwitchFixtures.*

  test("a switch elaborates to a first-class Stmt.Switch, not nested When"):
    class Fsm extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) {
        is(0.U(2)) { y := 1.U(8) }
        is(1.U(2)) { y := 2.U(8) }
      }
    val stmts = driverFor(new Fsm, "y")
    assertEquals(stmts.length, 2, "the default assignment, then the switch")
    val sw = stmts(1) match
      case s: Stmt.Switch => s
      case other => fail(s"expected a Stmt.Switch, got $other")
    assertEquals(sw.cases.length, 2)
    assert(!stmts.exists(_.isInstanceOf[Stmt.When]),
      "R22: a switch is not lowered to conditionals")

  test("alternatives and their alias values keep elaboration order"):
    class Fsm extends Fix:
      val sel = In(UInt(3))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) {
        is(4.U(3)) { y := 1.U(8) }
        is(0.U(3), 2.U(3), 1.U(3)) { y := 2.U(8) }
        is(7.U(3)) { y := 3.U(8) }
      }
    val sw = onlySwitch(new Fsm, "y")
    assertEquals(sw.cases.map(_.values.map(litOf)), List(List(4), List(0, 2, 1), List(7)),
      "case order is source order, and alias order is argument order")

  test("`for`, `foreach`, and handwritten alternatives produce identical IR"):
    val table = List(0 -> 11, 1 -> 22, 2 -> 33)
    class Loop extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) {
        for entry <- table do is(entry._1.U(2)) { y := entry._2.U(8) }
      }
    class Each extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) {
        table.foreach { entry => is(entry._1.U(2)) { y := entry._2.U(8) } }
      }
    class Written extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) {
        is(0.U(2)) { y := 11.U(8) }
        is(1.U(2)) { y := 22.U(8) }
        is(2.U(2)) { y := 33.U(8) }
      }
    // Locations differ — they are the point of `Loc`, and the three are three
    // different pieces of source. Everything else must agree exactly.
    val shapes = List(new Loop, new Each, new Written).map(m => stripLocs(onlySwitch(m, "y")))
    assertEquals(shapes(0), shapes(2), "a `for` producer equals the handwritten form")
    assertEquals(shapes(1), shapes(2), "a `foreach` producer equals the handwritten form")

  test("a helper method may open alternatives while the switch is open"):
    class Helpers extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      def entry(value: Int, result: Int): Unit =
        is(value.U(2)) { y := result.U(8) }
      y := 0.U(8)
      switch(sel) {
        entry(0, 11)
        entry(1, 22)
      }
    val sw = onlySwitch(new Helpers, "y")
    assertEquals(sw.cases.map(_.values.map(litOf)), List(List(0), List(1)))

  test("a nested `when` inside an alternative stays nested"):
    class Nested extends Fix:
      val sel = In(UInt(2))
      val go = In(Bool())
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) {
        is(0.U(2)) {
          y := 1.U(8)
          when(go) { y := 2.U(8) }
        }
      }
    val sw = onlySwitch(new Nested, "y")
    assertEquals(sw.cases.head.stmts.length, 2)
    assert(sw.cases.head.stmts(1).isInstanceOf[Stmt.When],
      "the `when` is a statement of the alternative, not a sibling of the switch")

  test("a nested `switch` inside an alternative stays nested"):
    class Nested extends Fix:
      val outer = In(UInt(2))
      val inner = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(outer) {
        is(0.U(2)) {
          switch(inner) {
            is(1.U(2)) { y := 7.U(8) }
          }
        }
      }
    val sw = onlySwitch(new Nested, "y")
    sw.cases.head.stmts match
      case (nested: Stmt.Switch) :: Nil =>
        assertEquals(nested.cases.map(_.values.map(litOf)), List(List(1)))
      case other => fail(s"expected one nested switch, got $other")

  test("a switch inside a `when` stays inside the branch"):
    class Mixed extends Fix:
      val go = In(Bool())
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      when(go) {
        switch(sel) {
          is(0.U(2)) { y := 1.U(8) }
        }
      }
    val stmts = driverFor(new Mixed, "y")
    stmts(1) match
      case Stmt.When(_, (nested: Stmt.Switch) :: Nil, Nil, _) =>
        assertEquals(nested.cases.length, 1)
      case other => fail(s"expected a `when` containing a switch, got $other")

  test("each driven signal keeps every alternative, including ones it ignores"):
    // The projection is per signal (R12: one signal, one driver block), but
    // the case structure is not the projection's to edit: an alternative that
    // does not write this signal is what says "here it holds its value".
    class Split extends Fix:
      val sel = In(UInt(2))
      val a = Out(UInt(8))
      val b = Out(UInt(8))
      a := 0.U(8)
      b := 0.U(8)
      switch(sel) {
        is(0.U(2)) { a := 1.U(8) }
        is(1.U(2)) { b := 2.U(8) }
        is(2.U(2)) { a := 3.U(8) }
      }
    val design = (new Split).elaborateDesign()
    val forA = onlySwitchOf(design, "a")
    val forB = onlySwitchOf(design, "b")
    assertEquals(forA.cases.map(_.values.map(litOf)), List(List(0), List(1), List(2)))
    assertEquals(forB.cases.map(_.values.map(litOf)), List(List(0), List(1), List(2)))
    assertEquals(forA.cases.map(_.stmts.length), List(1, 0, 1))
    assertEquals(forB.cases.map(_.stmts.length), List(0, 1, 0))

  test("an absent default and an empty one are different statements"):
    class Absent extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) { is(0.U(2)) { y := 1.U(8) } }
    class Empty extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      val z = Out(UInt(8))
      y := 0.U(8)
      z := 0.U(8)
      switch(sel) {
        is(0.U(2)) { y := 1.U(8) }
        default { z := 9.U(8) }
      }
    assertEquals(onlySwitch(new Absent, "y").defaultStmts, None)
    assertEquals(onlySwitch(new Empty, "y").defaultStmts, Some(Nil),
      "the default exists for this signal's projection even though it writes another")

/** Shared fixtures: a clocked module base, and IR lookup helpers. */
object SwitchFixtures:
  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def driverFor(m: Module, name: String): List[Stmt] =
    driverOf(m.elaborateDesign(), name)

  def driverOf(design: Design, name: String): List[Stmt] =
    val top = design.top
    val id = top.signals.find(_.name == name).map(_.id).getOrElse(
      throw AssertionError(s"no signal named $name"))
    top.drivers.find(_.signalId == id).map(_.stmts).getOrElse(
      throw AssertionError(s"no driver block for $name"))

  def onlySwitch(m: Module, name: String): Stmt.Switch =
    onlySwitchOf(m.elaborateDesign(), name)

  def onlySwitchOf(design: Design, name: String): Stmt.Switch =
    driverOf(design, name).collect { case s: Stmt.Switch => s } match
      case one :: Nil => one
      case other => throw AssertionError(s"expected exactly one switch, got ${other.length}")

  def litOf(e: Expr): Int = e match
    case Expr.Lit(v, _, _) => v.toInt
    case other => throw AssertionError(s"expected a literal, got $other")

  /** Erase source locations so two spellings of one design compare equal. */
  def stripLocs(s: Stmt): Stmt = s match
    case Stmt.Assign(t, e, b, _) => Stmt.Assign(t, e, b, Loc.NoLoc)
    case Stmt.When(c, t, e, _) =>
      Stmt.When(c, t.map(stripLocs), e.map(stripLocs), Loc.NoLoc)
    case Stmt.Switch(kind, selector, cases, defaults, _) =>
      Stmt.Switch(kind, selector,
        cases.map(c => SwitchCase(c.values, c.stmts.map(stripLocs), Loc.NoLoc)),
        defaults.map(_.map(stripLocs)), Loc.NoLoc)
    case Stmt.MemWrite(id, a, d, _) => Stmt.MemWrite(id, a, d, Loc.NoLoc)
    case Stmt.Check(b, c, m, _) => Stmt.Check(b, c, m, Loc.NoLoc)

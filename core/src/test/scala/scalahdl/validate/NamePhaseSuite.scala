package scalahdl.validate

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic, Code}

/** R1 (minimal) — name collisions, the final M02 checkbox. A duplicate signal
  * name is an elaboration error. Both declaration lines appear in the
  * diagnostic so the user sees each `val` that collided.
  *
  * Two Scala `val`s of the same name are a compile error, so the realistic
  * duplicate (a port and an internal signal, or two bundle fields that flatten
  * to the same leaf name) is exercised by hand-building a `Module` with a
  * duplicate and running the phase directly. The integrated path
  * (`Module.elaborate`) runs the same phase. */
class NamePhaseSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def expectFailure(body: => Module): List[Diagnostic] =
    val ex = intercept[ValidationFailed] { body.elaborateDesign() }
    ex.diagnostics

  test("a duplicate signal name is an error (R1 collision)"):
    // Hand-built module: two internal signals named `x` at different lines.
    // This is the shape the builder now produces (it no longer throws on a
    // duplicate; it records both and lets the NamePhase report).
    val handBuilt = Module(
      name = "Hand",
      ports = List(Port("clk", Direction.In, Type.Bool, Loc("f", 1, 1), SignalId(0))),
      signals = List(
        Signal(SignalId(0), "clk", Type.Bool,    SignalKind.Wire, None, Loc("f", 1, 1)),
        Signal(SignalId(1), "x",   Type.UInt(8), SignalKind.Wire, None, Loc("f", 2, 1)),
        Signal(SignalId(2), "x",   Type.UInt(8), SignalKind.Wire, None, Loc("f", 3, 1))),
      memories = Nil, instances = Nil, drivers = Nil, loc = Loc.NoLoc)
    val errs = (new NamePhase).run(new Validator.Ctx(handBuilt))
    assert(errs.exists(_.code == Code.NAME_COLLISION), s"expected NAME_COLLISION: ${errs.map(_.render)}")
    val d = errs.find(_.code == Code.NAME_COLLISION).get
    assert(d.summary.contains("x"), s"should name the colliding name: ${d.summary}")
    // The primary location is the first declaration; the second is a related loc.
    assert(d.primaryLoc.line == 2, s"primary loc should be first decl: ${d.primaryLoc}")
    assert(d.related.length == 1, s"expected one related loc: ${d.related}")
    assert(d.related.head.loc.line == 3, s"related loc should point at the second decl: ${d.related}")

  test("unique names elaborate cleanly (no false positive)"):
    class Good extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val y = Out(UInt(8))
      y := a
    val design = (new Good).elaborateDesign()
    assert(design.top.signals.exists(_.name == "a"))
    assert(design.top.signals.exists(_.name == "b"))
end NamePhaseSuite

package scalahdl.naming

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, Builder}
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh

/** R1 — Name preservation via spliceOwner (the bet, Appendix C). A `val`
  * declared at module top level, inside a helper `def`, and inside a `for`
  * loop body all carry their written name into the IR. Anonymous construction
  * (not bound to a `val`) carries no name and is rejected (R2). */
class NameCaptureSuite extends munit.FunSuite:

  // A minimal module that supplies a clock domain so `Reg`/internal signals
  // can be declared. clk/rst are ports (no domain needed for ports).
  class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  /** A `val` declared at module top level keeps its name. */
  class TopLevel extends Fix:
    val cnt = Reg(UInt(8), init = 0.U(8))
    when(clk) { cnt := cnt + 1.U(8) }

  test("top-level val name survives to IR"):
    val m = new TopLevel
    val design = m.elaborateDesign()
    val names = design.top.signals.map(_.name).toSet
    assert(names.contains("cnt"), s"expected 'cnt' in $names")

  /** A `val` inside a helper `def` keeps its name. */
  class DefLocal extends Fix:
    def helper(w: Int): DslSignal =
      val internal = Reg(UInt(w), init = 0.U(w))
      internal
    val lim = helper(8)

  test("def-local val name survives to IR"):
    val m = new DefLocal
    val design = m.elaborateDesign()
    val names = design.top.signals.map(_.name).toSet
    assert(names.contains("internal"), s"expected 'internal' in $names")

  /** A `val` inside a `for` loop body keeps its name. */
  class ForLocal extends Fix:
    var acc: DslSignal = null
    for i <- 0 until 1 do
      val mark = Reg(UInt(8), init = 0.U(8))
      acc = mark

  test("for-local val name survives to IR"):
    val m = new ForLocal
    val design = m.elaborateDesign()
    val names = design.top.signals.map(_.name).toSet
    assert(names.contains("mark"), s"expected 'mark' in $names")

  /** Anonymous construction (not bound to a `val`) is rejected — R2: anonymous
    * trees never receive a declaration. The Counter binds every signal, so
    * this is the loud failure that keeps R2 honest. */
  class Anonymous extends Fix:
    val cnt = Reg(UInt(8), init = 0.U(8))
    when(clk) { cnt := cnt + 1.U(8) }
    // `cnt + 1.U(8)` is anonymous — fine, it's an expression, not a signal.
    // But a bare `Reg(...)` not bound to a val is anonymous hardware.

  test("anonymous hardware (unbound Reg) is rejected"):
    val err = intercept[Builder.ElabError]:
      class Anon extends Fix:
        // a bare Reg as a statement expression, not bound to a val — anonymous
        Reg(UInt(8), init = 0.U(8))
      val m = new Anon
      m.elaborateDesign()
    assert(err.msg.contains("anonymous"), s"got: ${err.msg}")

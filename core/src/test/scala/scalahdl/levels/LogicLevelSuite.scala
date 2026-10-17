package scalahdl.levels

import scalahdl.dsl.{*, given}
import scalahdl.elab.{BlackBox, Module}
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.Emission

/** R29 — the logic-level report.
  *
  * The report's value is its *ranking*, so most of what is worth pinning is
  * order rather than absolute numbers: the deep cone must come first, the
  * wide selection must outrank the gate chain, and a cone that crosses an
  * instance must carry what the child costs. */
class LogicLevelSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def reportOf(m: => Module, name: String): String =
    val emitted = new Emission("test").emit(m.elaborateDesign())
    emitted.reports.collectFirst { case (file, body) if file == name => body }
      .getOrElse(fail(s"no $name in ${emitted.reports.map(_._1)}"))

  /** The endpoints, deepest first, as the ranking table lists them. */
  def rankedEndpoints(report: String): List[String] =
    report.linesIterator
      .dropWhile(!_.startsWith("Deepest cones"))
      .filter(line => line.matches("""^\s+\d+\s+\S.*"""))
      .map(_.trim.split("\\s+").drop(1).head)
      .toList

  test("a report is written for every module, plus one roll-up for the design"):
    class Leaf extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val a   = In(UInt(8))
      val y   = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      y := a + 1.U(8)
    class Top extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val u = inst(new Leaf)
      u.a := d
      q := u.y
    val emitted = new Emission("test").emit((new Top).elaborateDesign())
    assert(emitted.reports.map(_._1).contains("Leaf.levels.rpt"))
    assert(emitted.reports.map(_._1).contains("Top.levels.rpt"))
    assert(emitted.reports.map(_._1).contains("design.levels.rpt"))

  test("the model is printed in the report that applies it, and disclaims timing"):
    class Small extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      q := d + 1.U(8)
    val report = reportOf(new Small, "Small.levels.rpt")
    assert(report.contains("ceil(log2 w)"), report)
    assert(report.contains("not a timing result"), report)

  test("the deeper cone is ranked first"):
    class Two extends Fix:
      val d = In(UInt(8))
      val shallow = Out(UInt(8))
      val deep    = Out(UInt(8))
      shallow := d & 1.U(8)
      deep    := ((d + 1.U(8)) + 2.U(8)) + 3.U(8)
    assertEquals(rankedEndpoints(reportOf(new Two, "Two.levels.rpt")).take(2),
      List("deep", "shallow"))

  test("a wide selection outranks a chain of gates — the entry the report exists for"):
    class Ranking extends Fix:
      val sel = In(UInt(8))
      val d   = In(UInt(8))
      val gates   = Out(UInt(8))
      val decoded = Out(UInt(8))
      gates := ((d & 1.U(8)) & 2.U(8)) & 4.U(8)
      decoded := 0.U(8)
      switch(sel) {
        for value <- 0 until 64 do is(value.U(8)) { decoded := (d ^ value.U(8)) }
      }
    assertEquals(rankedEndpoints(reportOf(new Ranking, "Ranking.levels.rpt")).head, "decoded")

  test("a cone that crosses an instance carries what the child costs"):
    class Adder extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val a   = In(UInt(8))
      val y   = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      y := ((a + 1.U(8)) + 2.U(8)) + 3.U(8)
    class Registered extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val a   = In(UInt(8))
      val y   = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val held = Reg(UInt(8), init = 0.U(8))
      held := a
      y := held
    class Through extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val u = inst(new Adder)
      u.a := d
      q := u.y
    class Blocked extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val u = inst(new Registered)
      u.a := d
      q := u.y
    // The child's own depth is what separates them: neither parent has any
    // logic of its own, and the registered child has no path at all.
    assert(reportOf(new Through, "Through.levels.rpt").contains("u.a"))
    val blocked = reportOf(new Blocked, "Blocked.levels.rpt")
    assert(blocked.contains("(none: no combinational logic"), blocked)

  test("what the graph assumed about a blackbox is printed in the report"):
    class Gate extends BlackBox:
      override val moduleName = "vendor_gate"
      val clk = In(Clock())
      val a   = In(UInt(8))
      val y   = Out(UInt(8))
      val here = enclosingDomain
      outputDomain(y, here)
    class Top extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      val u = inst(new Gate)
      u.clk := clk
      u.a := d + 1.U(8)
      q := u.y
    val report = reportOf(new Top, "Top.levels.rpt")
    assert(report.contains("declares no combinational path"), report)

  test("a truncated listing says what it dropped"):
    class Wide extends Fix:
      val d = In(UInt(8))
      val en = In(Bool())
      val q = Out(UInt(8))
      val cells = Reg(Vec(40, UInt(8)))
      for i <- 0 until 40 do
        when(en) { cells(i) := d + i.U(8) }
      q := cells(0)
    val report = reportOf(new Wide, "Wide.levels.rpt")
    assert(report.contains("shallower cones not listed"), report)

  test("two emissions of one design produce identical reports"):
    class Small extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      q := d + 1.U(8)
    val design = (new Small).elaborateDesign()
    assertEquals(new Emission("test").emit(design).reports,
                 new Emission("test").emit(design).reports)

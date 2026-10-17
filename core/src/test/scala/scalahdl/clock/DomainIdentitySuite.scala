package scalahdl.clock

import scalahdl.dsl.{*, given}
import scalahdl.clock.{ClockRef, ResetSpec, ResetKind}
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.emit.VerilogEmitter

/** R05.5 — A domain is its clock, in the type as well as the check.
  *
  * Every test here is the same shape: one clock, two reset shapes — which R13
  * makes the ordinary case and M27's R05 ruled is one domain — asked a question
  * that used to be answered with case-class `==` on the whole record.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M30-domain-integrity/R05.5-domain-identity-in-the-type.md`. */
class DomainIdentitySuite extends munit.FunSuite:

  /** One clock, two reset shapes. The pair R13 exists to make ordinary. */
  abstract class OneClockTwoShapes extends Module:
    val clk   = In(Clock())
    val rst_n = In(Reset(ActiveLow))
    given reset: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
    lazy val unreset: ClockDomain = ClockDomain(clk, ResetKind.None)

  // ---- the relation itself ----------------------------------------------

  test("two reset shapes on one clock are one domain, and differ as recipes"):
    class M extends OneClockTwoShapes:
      val q = Out(UInt(8))
      val a = Reg(UInt(8), init = 0)
      val b = Reg(UInt(8), domain = unreset)
      b := a                       // one clock — not a crossing
      a := a + 1
      q := b
    val design = (new M).elaborateDesign()   // elaborates: no CDC diagnostic
    val m = new M
    assert(!m.reset.isForeignTo(m.unreset), "same clock is not foreign")
    assert(!m.unreset.isForeignTo(m.reset), "and not in the other direction")
    assertEquals(m.reset.clock, m.unreset.clock, "identity is the clock")
    assertNotEquals(m.reset.reset, m.unreset.reset, "the recipes still differ")
    assert(design.top.signals.exists(_.name == "b"))

  test("a different clock stays foreign however the resets agree"):
    class M extends Module:
      val clk     = In(Clock())
      val slowClk = In(Clock())
      val rst_n   = In(Reset(ActiveLow))
      given fast: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
      lazy val slow: ClockDomain = ClockDomain(slowClk, rst_n, ResetKind.Async, ActiveLow)
    val m = new M
    assert(m.slow.isForeignTo(m.fast))
    assert(m.fast.isForeignTo(m.slow))

  test("a domain with no clock is neutral in both directions"):
    val some = scalahdl.clock.ClockDomain(ClockRef(SignalId(7), "clk"), ResetSpec.none)
    assert(!scalahdl.clock.ClockDomain.noClock.isForeignTo(some))
    assert(!some.isForeignTo(scalahdl.clock.ClockDomain.noClock))
    assert(scalahdl.clock.ClockDomain.noClock.isUnclocked)
    assert(!some.isUnclocked)

  // ---- defect 1: WriteFirst silently became ReadFirst --------------------

  test("a WriteFirst read bypasses writes on its clock, whatever the reset shape"):
    def emit(splitRecords: Boolean): String =
      class M(split: Boolean) extends OneClockTwoShapes:
        val addr = In(UInt(4)); val din = In(UInt(8)); val we = In(Bool())
        val dout = Out(UInt(8))
        val mem = BlockRam(16, UInt(8))
        mem.write(addr, din, we)                       // recorded in `reset`
        val q = if split then { given ClockDomain = unreset
                                mem.readSync(addr, WriteFirst) }
                else mem.readSync(addr, WriteFirst)
        dout := q
      new VerilogEmitter("0.1.0-SNAPSHOT")
        .emitModule((new M(splitRecords)).elaborateDesign().top, "M")
    val together = emit(false)
    val split    = emit(true)
    assert(together.contains("? din :"), s"baseline bypass: $together")
    assert(split.contains("? din :"),
      s"a WriteFirst read recorded under a different reset shape lost its " +
      s"bypass and silently became ReadFirst (R05.5): $split")

  test("a memory's blocks are keyed on the clock, not on the reset shape"):
    class M extends OneClockTwoShapes:
      val addr = In(UInt(4)); val din = In(UInt(8)); val we = In(Bool())
      val dout = Out(UInt(8))
      val mem = BlockRam(16, UInt(8))
      mem.write(addr, din, we)
      val q = { given ClockDomain = unreset; mem.readSync(addr, ReadFirst) }
      dout := q
    val v = new VerilogEmitter("0.1.0-SNAPSHOT")
      .emitModule((new M).elaborateDesign().top, "M")
    // One block per access (R15.1), and here there are two: a write and a read
    // recorded under different reset shapes on the one clock. What this test
    // guards is that the *shape* adds nothing — both blocks are `posedge clk`,
    // and neither carries a reset, so a memory is never split onto two edges.
    val blocks = v.linesIterator.count(_.contains("always @(posedge clk)"))
    assertEquals(blocks, 2,
      s"a memory's accesses are one block each, all on the clock they name " +
      s"and none on a reset shape (R15.1, R05.5): $v")
    assertEquals(v.linesIterator.count(_.contains("always @(posedge")), blocks,
      s"a reset shape put a memory on an edge of its own: $v")

  // ---- defect 2: a real crossing was blessed away ------------------------

  test("a crossing into a memory is rejected whether one record or two name its clock"):
    def elaborate(twoRecords: Boolean): Either[String, Unit] =
      class M(two: Boolean) extends OneClockTwoShapes:
        val slowClk = In(Clock())
        val slowRst = In(Reset(ActiveLow))
        val addr = In(UInt(4)); val we = In(Bool())
        val dout = Out(UInt(8))
        lazy val slow: ClockDomain =
          ClockDomain(slowClk, slowRst, ResetKind.Async, ActiveLow)
        val foreign = Reg(UInt(8), init = 0, domain = slow)
        foreign := foreign + 1
        val mem = BlockRam(16, UInt(8))
        mem.write(addr, foreign, we)                   // the crossing
        val q = if two then { given ClockDomain = unreset
                              mem.readSync(addr, ReadFirst) }
                else mem.readSync(addr, ReadFirst)
        dout := q
      scala.util.Try((new M(twoRecords)).elaborateDesign()).toEither.left.map {
        case v: scalahdl.diag.ValidationFailed => v.diagnostics.map(_.summary).mkString(" | ")
        case e                                 => String.valueOf(e.getMessage)
      }.map(_ => ())
    val one = elaborate(false)
    val two = elaborate(true)
    assert(one.isLeft, "baseline: the crossing is rejected")
    assert(two.isLeft,
      "adding a read port under a second reset shape on the same clock must " +
      "not switch the write-data CDC check off (R05.5)")
    // Not merely both rejected — rejected *identically*. The second reset
    // shape is not a fact about the crossing and must not change a word of it.
    assertEquals(two, one)
    assert(one.left.toOption.exists(_.contains("belongs to `slowClk`")), s"$one")

  // ---- defect 3: a phantom dual-domain crossing in the report -------------

  test("the CDC report does not invent a dual-domain crossing on one clock"):
    class M extends OneClockTwoShapes:
      val addr = In(UInt(4)); val din = In(UInt(8)); val we = In(Bool())
      val dout = Out(UInt(8))
      val mem = BlockRam(16, UInt(8))
      mem.write(addr, din, we)
      val q = { given ClockDomain = unreset; mem.readSync(addr, ReadFirst) }
      dout := q
    val report = scalahdl.core.CdcReport.render((new M).elaborateDesign().top)
    assert(!report.contains("DualDomainMemory") && !report.contains("dual"),
      s"one clock is one domain — no crossing to report (R05.5): $report")

package scalahdl.cdc

import munit.FunSuite
import scalahdl.elab.Module
import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.core.{CdcReport, Type}
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.emit.{Emission, VerilogEmitter}

final class CdcSuite extends FunSuite:
  private abstract class DualDomain extends Module:
    val clkA = In(Clock())
    val clkB = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clkA, ResetKind.None)
    val domainB = ClockDomain(clkB, ResetKind.None)

  test("direct foreign-domain Bool read is CDC_UNSAFE_CROSSING"):
    class Top extends DualDomain:
      val source = Reg(Bool())
      withClock(domainB):
        val sink = Reg(Bool())
        sink := source

    val failure = intercept[ValidationFailed]((new Top).elaborate())
    assertEquals(failure.diagnostics.head.code, Code.CDC_UNSAFE_CROSSING)
    assert(failure.diagnostics.head.summary.contains("clkA"))
    assert(failure.diagnostics.head.summary.contains("clkB"))

  test("SyncChain accepts Bool and emits destination-domain stages"):
    class Top extends DualDomain:
      val source = Reg(Bool())
      withClock(domainB):
        val synced = SyncChain(source)
        val sink = Reg(Bool())
        sink := synced

    val module = (new Top).elaborate()
    val syncStages = module.signals.filter(_.name.startsWith("sync"))
    assertEquals(syncStages.size, 2)
    assert(syncStages.forall(_.domain.clkName == "clkB"))
    assert(CdcReport.render(module).contains("source: clkA -> clkB via SyncChain(2)"))

  test("SyncChain rejects ordinary multi-bit Bits and accepts GrayCode"):
    class Bad extends DualDomain:
      val source = Reg(Bits(4))
      withClock(domainB):
        val synced = SyncChain(source)
    intercept[CdcElaborationError](new Bad)

    class Good extends DualDomain:
      val source = Reg(GrayCode(4))
      withClock(domainB):
        val synced = SyncChain(source)
    (new Good).elaborate()

  test("unsafe hatch requires a reason, reports it, and emits adjacent comment"):
    class Empty extends DualDomain:
      val source = Reg(Bool())
      withClock(domainB):
        val sink = Reg(Bool())
        sink := unsafeCrossDomain(source, "")
    intercept[CdcElaborationError](new Empty)

    class Top extends DualDomain:
      val source = Reg(Bool())
      withClock(domainB):
        val sink = Reg(Bool())
        sink := unsafeCrossDomain(source, "quasi-static before enable")

    val module = (new Top).elaborate()
    val report = CdcReport.render(module)
    assert(report.contains("Unsafe"))
    assert(report.contains("quasi-static before enable"))
    val verilog = new VerilogEmitter("test").emitModule(module, "Top")
    assert(verilog.contains("/* ASYNC_REG: quasi-static before enable */ source"), verilog)

  test("emission provides deterministic module CDC report files"):
    class Top extends DualDomain:
      val source = Reg(Bool())
      withClock(domainB):
        val synced = SyncChain(source)

    val design = (new Top).elaborateDesign()
    val first = new Emission("test").emit(design, "Top")
    val second = new Emission("test").emit(design, "Top")
    assertEquals(first.reports, second.reports)
    assertEquals(first.reports.map(_._1),
      List("Top.cdc.rpt", "Top.levels.rpt", "design.levels.rpt"))
    assert(first.reports.head._2.contains("Safe"))
    assert(first.reports.head._2.contains("Unsafe\n(none)"))

  test("gray conversion and increment preserve the distinct type"):
    class Top extends DualDomain:
      val binary = Reg(UInt(4))
      val gray = Reg(GrayCode(4))
      gray := binary.asGray
      gray := gray.grayIncrement

    val module = (new Top).elaborate()
    assertEquals(module.signals.find(_.name == "gray").map(_.typeOf), Some(Type.GrayCode(4)))

    val values = (0 until 16).map(n => n ^ (n >> 1))
    values.sliding(2).foreach { pair =>
      assertEquals(Integer.bitCount(pair.head ^ pair.last), 1)
    }

  test("dual-domain memory is blessed, while each pointer must be local or synchronized"):
    class Good extends DualDomain:
      val writeAddress = Reg(UInt(4))
      val data = Reg(UInt(8))
      val memory = BlockRam(16, UInt(8))
      memory.write(writeAddress, data)
      withClock(domainB):
        val readAddress = Reg(UInt(4))
        val value = memory.readSync(readAddress, ReadFirst)

    val good = (new Good).elaborate()
    assert(CdcReport.render(good).contains("DualDomainMemory(memory)"))

    class Bad extends DualDomain:
      val addressA = Reg(UInt(4))
      val data = Reg(UInt(8))
      val memory = BlockRam(16, UInt(8))
      memory.write(addressA, data)
      withClock(domainB):
        val value = memory.readSync(addressA, ReadFirst)

    val failure = intercept[ValidationFailed]((new Bad).elaborate())
    assertEquals(failure.diagnostics.head.code, Code.CDC_UNSAFE_CROSSING)

  // ---- R05: a domain is its clock
  //
  // R13 splits a register's reset in two — the shape belongs to the domain and
  // the value to the register — so one clock legitimately carries more than one
  // reset shape. A vendored peripheral does it routinely: an asynchronously
  // reset status register beside a synchronously reset one beside a pipeline
  // stage nothing resets at all. Comparing whole `ClockDomain` records made
  // those reads a crossing, which is a metastability claim about two registers
  // that share an edge.

  test("two reset shapes on one clock are one domain, and cross nothing"):
    class Top extends Module:
      val clk   = In(Clock())
      val rst   = In(Reset(ActiveHigh))
      val d     = In(Bool())
      val q     = Out(Bool())
      given unreset: ClockDomain = ClockDomain(clk, ResetKind.None)
      val stage = Reg(Bool())
      stage := d
      withClock(ClockDomain(clk, rst, ResetKind.Async, ActiveHigh)):
        val latched = Reg(Bool(), init = False)
        latched := stage                  // async ← unreset, same clock
        q := latched
      withClock(ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)):
        val counted = Reg(UInt(4), init = 0)
        when(stage) { counted := counted + 1 }

    val module = (new Top).elaborate()
    // and the two shapes really are emitted, so this is not one domain by
    // accident of the port list
    val verilog = new Emission("test").emit((new Top).elaborateDesign(), "Top").files.head._2
    assert(verilog.contains("always @(posedge clk or posedge rst)"), verilog)
    assert(verilog.contains("always @(posedge clk) begin"), verilog)
    // nothing crossed, so the report has nothing in it
    assert(!CdcReport.render(module).contains("->"), CdcReport.render(module))

  test("a different clock is still foreign, however the resets agree"):
    class Top extends Module:
      val clkA = In(Clock())
      val clkB = In(Clock())
      val rst  = In(Reset(ActiveHigh))
      given a: ClockDomain = ClockDomain(clkA, rst, ResetKind.Async, ActiveHigh)
      val source = Reg(Bool(), init = False)
      withClock(ClockDomain(clkB, rst, ResetKind.Async, ActiveHigh)):
        val sink = Reg(Bool(), init = False)
        sink := source

    val failure = intercept[ValidationFailed]((new Top).elaborate())
    assertEquals(failure.diagnostics.head.code, Code.CDC_UNSAFE_CROSSING)

  // ---- R05.6: related clocks
  //
  // Three taps of one PLL, or a divided clock and what it was divided from: a
  // read between them has no metastability in it, and until now it had to be
  // marked `unsafeCrossDomain` at every leaf with the same sentence attached.
  // A module states the family once, where the clocks are.

  private abstract class OnePll extends Module:
    val clk     = In(Clock())
    val mac_clk = In(Clock())
    given memory: ClockDomain = ClockDomain(clk, ResetKind.None)
    val mac = ClockDomain(mac_clk, ResetKind.None)

  test("clocks declared one source cross nothing, and the report says so"):
    class Top extends OnePll:
      relatedClocks(clk, mac_clk)
      val source = Reg(Bool())
      withClock(mac):
        val sink = Reg(Bool())
        sink := source

    val module = (new Top).elaborate()
    val report = CdcReport.render(module)
    assert(report.startsWith("CDC report: Top\n\nRelated clocks\n- clk, mac_clk\n\n"), report)
    assert(report.contains("Unsafe\n(none)"), report)

  test("a clock outside the family is still foreign"):
    class Top extends OnePll:
      val other = In(Clock())
      relatedClocks(clk, mac_clk)
      val source = Reg(Bool())
      withClock(ClockDomain(other, ResetKind.None)):
        val sink = Reg(Bool())
        sink := source

    val failure = intercept[ValidationFailed]((new Top).elaborate())
    assertEquals(failure.diagnostics.head.code, Code.CDC_UNSAFE_CROSSING)

  test("relatedClocks takes this module's clocks, each once"):
    class NotAClock extends OnePll:
      val enable = In(Bool())
      relatedClocks(clk, enable)
    intercept[CdcElaborationError](new NotAClock)

    class Twice extends OnePll:
      relatedClocks(clk, clk)
    intercept[CdcElaborationError](new Twice)

    class Child extends Module:
      val clk = In(Clock())
      given own: ClockDomain = ClockDomain(clk, ResetKind.None)
      val q = Out(Bool())
      q := False
    class HandedDown extends OnePll:
      val u = inst(new Child)
      relatedClocks(clk, u.clk)           // the child's input is ours to drive, not to relate
    intercept[CdcElaborationError](new HandedDown)

  test("two statements that name a common clock are one family"):
    class Top extends OnePll:
      val core_clk = In(Clock())
      relatedClocks(clk, mac_clk)
      relatedClocks(mac_clk, core_clk)
      val source = Reg(Bool())
      withClock(ClockDomain(core_clk, ResetKind.None)):
        val sink = Reg(Bool())
        sink := source

    val module = (new Top).elaborate()
    assertEquals(module.relatedClocks.size, 1)
    assert(CdcReport.render(module).contains("- clk, mac_clk, core_clk\n"))

  test("a derived clock is related by saying so, not by the cast (R13.9)"):
    abstract class Divided extends Module:
      val clk = In(Clock())
      given system: ClockDomain = ClockDomain(clk, ResetKind.None)
      val half = Reg(Bool(), powerUp = False)
      half := !half
      val core_clk = Wire(Clock())
      core_clk := half.asClock

    class Unsaid extends Divided:
      val latched = Reg(Bool(), powerUp = False)
      withClock(ClockDomain(core_clk, ResetKind.None)):
        val as = Reg(Bool(), powerUp = False)
        as := !as
        latched := as                     // core_clk data read on clk
    val failure = intercept[ValidationFailed]((new Unsaid).elaborate())
    assertEquals(failure.diagnostics.head.code, Code.CDC_UNSAFE_CROSSING)

    class Said extends Divided:
      relatedClocks(clk, core_clk)
      val latched = Reg(Bool(), powerUp = False)
      withClock(ClockDomain(core_clk, ResetKind.None)):
        val as = Reg(Bool(), powerUp = False)
        as := !as
        latched := as
    val report = CdcReport.render((new Said).elaborate())
    assert(report.contains("Related clocks\n- clk, core_clk\n"), report)
    assert(report.contains("Unsafe\n(none)"), report)

  test("a child's related clocks are a contract its parent is held to"):
    class Machine extends Module:
      val clk      = In(Clock())
      val core_clk = In(Clock())
      relatedClocks(clk, core_clk)
      given system: ClockDomain = ClockDomain(clk, ResetKind.None)
      val q = Out(Bool())
      val strobe = Wire(Bool())
      withClock(ClockDomain(core_clk, ResetKind.None)):
        val as = Reg(Bool(), powerUp = False)
        as := !as
        strobe := as
      q := strobe

    class Met extends OnePll:
      val core_clk = In(Clock())
      relatedClocks(clk, mac_clk, core_clk)
      val out = Out(Bool())
      val u = inst(new Machine)
      u.clk      := mac_clk
      u.core_clk := core_clk
      out := u.q
    (new Met).elaborateDesign()

    class Broken extends OnePll:
      val core_clk = In(Clock())          // never declared one source with the rest
      relatedClocks(clk, mac_clk)
      val out = Out(Bool())
      val u = inst(new Machine)
      u.clk      := mac_clk
      u.core_clk := core_clk
      out := u.q
    val failure = intercept[ValidationFailed]((new Broken).elaborateDesign())
    val head = failure.diagnostics.head
    assertEquals(head.code, Code.RELATED_CLOCKS_REQUIRED)
    assert(head.summary.contains("`u` (Machine) declares `clk`, `core_clk` one source"), head.summary)
    assert(head.summary.contains("`clk` from `mac_clk`, `core_clk` from `core_clk`"), head.summary)
    assert(head.fix.exists(_.contains("relatedClocks(mac_clk, core_clk)")), head.fix)

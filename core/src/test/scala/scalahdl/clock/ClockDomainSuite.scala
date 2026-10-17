package scalahdl.clock

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.diag.{ValidationFailed, Diagnostic, Code}
import scalahdl.emit.VerilogEmitter

/** R13 / R13.1 — Clock domains, reset polarity/kind, and Clock/Reset types.
  * Each test maps to a checkbox in `roadmap/open/M04-scalar-types/README.md`. */
class ClockDomainSuite extends munit.FunSuite:

  /** A module with explicit Clock/Reset ports and a configurable domain. */
  abstract class Fix extends Module:
    val clk   = In(Clock())
    val rst_n = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Sync, ActiveHigh)

  // ---- R13: reset branches from domain kind + polarity -----------------

  test("async-active-low domain emits posedge clk or negedge rst_n with if (!rst_n)"):
    class AsyncLow extends Module:
      val clk   = In(Clock())
      val rst_n = In(Reset(ActiveLow))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
      val cnt = Reg(UInt(8), init = 0.U(8))
      cnt := cnt + 1.U(8)
    val design = (new AsyncLow).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "AsyncLow")
    assert(v.contains("always @(posedge clk or negedge rst_n)"),
      s"async active-low sensitivity list: $v")
    assert(v.contains("if (!rst_n)"),
      s"async active-low reset condition: $v")

  test("switching to sync-active-high changes only the domain declaration"):
    class SyncHigh extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val cnt = Reg(UInt(8), init = 0.U(8))
      cnt := cnt + 1.U(8)
    val design = (new SyncHigh).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "SyncHigh")
    assert(v.contains("always @(posedge clk)"),
      s"sync active-high sensitivity list: $v")
    assert(v.contains("if (rst)"),
      s"sync active-high reset condition: $v")
    // The register code is untouched — same body, different domain shape.
    assert(v.contains("cnt <= (cnt + 8'd1)"),
      s"register body unchanged: $v")

  test("a register without init emits no reset branch"):
    class NoInit extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val cnt = Reg(UInt(8))   // no init = no reset branch
      cnt := cnt + 1.U(8)
    val design = (new NoInit).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "NoInit")
    assert(!v.contains("if (rst)"),
      s"no reset branch without init: $v")
    assert(v.contains("always @(posedge clk)"),
      s"still clocked: $v")

  test("an init-less register in an async domain drops the reset edge with it"):
    class AsyncNoInit extends Module:
      val clk   = In(Clock())
      val rst   = In(Reset(ActiveHigh))
      val rst_n = In(Reset(ActiveLow))
      val high  = ClockDomain(clk, rst,   ResetKind.Async, ActiveHigh)
      val low   = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
      // One domain, two registers: the reset *shape* is the domain's, but
      // whether there is a reset at all is the register's, so the two blocks
      // differ. `acc` is the same question at the other polarity.
      val cnt  = { given ClockDomain = high; Reg(UInt(8), init = 0.U(8)) }
      val free = { given ClockDomain = high; Reg(UInt(8)) }
      val acc  = { given ClockDomain = low;  Reg(UInt(8)) }
      given ClockDomain = high
      cnt  := cnt + 1.U(8)
      free := free + 1.U(8)
      { given ClockDomain = low; acc := acc + cnt }
    val design = (new AsyncNoInit).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "AsyncNoInit")
    // A reset edge with no branch to answer it clocks the input in on the
    // reset — the opposite of the hold R13 promises.
    assertEquals(v.linesIterator.count(_.contains("always @(posedge clk)")), 2,
      s"both init-less registers are clocked and nothing more: $v")
    assertEquals(v.linesIterator.count(_.contains("always @(posedge clk or posedge rst)")), 1,
      s"the register that does name a reset value keeps its edge: $v")
    assert(!v.contains("negedge rst_n"),
      s"the active-low init-less register loses its edge too: $v")

  // ---- R13.1: Clock/Reset distinct from Bool ----------------------------

  test("In(Clock()) emits an input port with the val's name"):
    class ClkPort extends Fix:
      val q = Out(UInt(8))
      q := 0.U(8)
    val design = (new ClkPort).elaborateDesign()
    val ports = design.top.ports.map(_.name)
    assert(ports.contains("clk"), s"clk port should appear: $ports")
    assert(ports.contains("rst_n"), s"rst_n port should appear: $ports")
    // No extra ports appear (R10 holds without exception).
    assertEquals(ports.length, 3, s"exactly 3 ports: $ports")

  test("passing a Bool to ClockDomain as clock is a runtime error"):
    class Bad extends Module:
      val clk = In(Bool())     // Bool, not Clock
      val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val cnt = Reg(UInt(8), init = 0.U(8))
    intercept[IllegalArgumentException]:
      (new Bad).elaborateDesign()

  test("using a Clock-typed signal in a + b is an error"):
    class BadArith extends Fix:
      val q = Out(UInt(8))
      q := clk + 1.U(8)    // clk is Clock — should error
    val errs = intercept[ValidationFailed]:
      (new BadArith).elaborateDesign()
    assert(errs.diagnostics.exists(_.code == Code.WIDTH_MISMATCH),
      s"expected WIDTH_MISMATCH for Clock in arithmetic: ${errs.diagnostics.map(_.render)}")
    val msg = errs.diagnostics.map(_.render).mkString
    assert(msg.contains("Clock"), s"should name Clock in the error: $msg")

  // ---- R13.5 — a reset read as data, once and explicitly
  //
  // R13.1 keeps a reset out of the data path so it cannot get there by
  // accident. A hierarchy needs it there on purpose: a child that spells the
  // reset the other way round, and a register that must hold while reset is
  // asserted rather than be cleared by it.

  test("`.asserted` is the reset's active level, with the polarity folded in"):
    class Both extends Module:
      val clk  = In(Clock())
      val rst  = In(Reset(ActiveHigh))
      val rstN = In(Reset(ActiveLow))
      val high = Out(Bool())
      val low  = Out(Bool())
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      high := rst.asserted
      low  := rstN.asserted

    val verilog = new VerilogEmitter("test").emitModule(
      (new Both).elaborate(), GenerationInfo("Both", Nil))
    assert(verilog.contains("high = rst;"), verilog)
    assert(verilog.contains("low = (!rstN);"), verilog)

  test("a register that holds while reset is asserted, rather than being cleared"):
    class Freeze extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val en  = In(Bool())
      val d   = In(UInt(8))
      val q   = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      val held = Reg(UInt(8))
      when(!rst.asserted & en) { held := d }
      q := held

    val verilog = new VerilogEmitter("test").emitModule(
      (new Freeze).elaborate(), GenerationInfo("Freeze", Nil))
    assert(verilog.contains("always @(posedge clk) begin"), verilog)
    assert(verilog.contains("if (((!rst) & en)) begin\n      held <= d;\n    end"), verilog)

  test("`.asserted` is the only way out, and Cat is not a second one"):
    class ThroughCat extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val q   = Out(UInt(1))
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      q := Cat(rst)

    val errs = intercept[ValidationFailed]((new ThroughCat).elaborateDesign())
    assert(errs.diagnostics.exists(_.code == Code.WIDTH_MISMATCH),
           errs.diagnostics.map(_.code).mkString(","))
    val msg = errs.diagnostics.map(_.render).mkString
    assert(msg.contains("asserted"), msg)

  test("...nor is Fill, which is the same concatenation with one part repeated"):
    class ThroughFill extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val q   = Out(UInt(2))
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      q := Fill(2, rst)

    val errs = intercept[ValidationFailed]((new ThroughFill).elaborateDesign())
    val msg = errs.diagnostics.map(_.render).mkString
    assert(msg.contains("asserted"), msg)

  test("`.asserted` refuses an ordinary signal, which is already data"):
    class NotAReset extends Module:
      val clk = In(Clock())
      val b   = In(Bool())
      val q   = Out(Bool())
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      q := b.asserted
    intercept[scalahdl.dsl.CastError](new NotAReset)

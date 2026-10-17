package scalahdl.blackbox

import scalahdl.dsl.{*, given}
import scalahdl.elab.{BlackBox, Module}
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.emit.Emission

class BlackBoxSuite extends munit.FunSuite:

  class Pll(val inputPeriod: Double, val feedbackMult: Int,
            val bandwidth: String = "OPTIMIZED") extends BlackBox:
    override val moduleName = "PLLE2_BASE"
    val clkIn   = In(Clock()).named("CLKIN")
    val rst     = In(Reset(ActiveHigh)).named("RST")
    val clkOut0 = Out(Clock()).named("CLKOUT0")
    val locked  = Out(Bool()).named("LOCKED")
    param("CLKIN1_PERIOD", inputPeriod)
    param("CLKFBOUT_MULT", feedbackMult)
    param("BANDWIDTH", bandwidth)
    val out0Domain = newDomain(clkOut0)
    outputDomain(locked, out0Domain)

  test("Pll emits only a verbatim parameterized instantiation with explicit connections"):
    class Top extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val pll = inst(new Pll(10.0, 8))
      pll.clkIn := clk
      pll.rst := rst

    val design = (new Top).elaborateDesign()
    assertEquals(design.modules.map(_.name), List("Top"))
    val emitted = new Emission("test").emit(design, "Top")
    assertEquals(emitted.files.map(_._1), List("Top.v"))
    val verilog = emitted.files.head._2
    assert(verilog.contains("PLLE2_BASE #("))
    assert(verilog.contains(".CLKIN1_PERIOD(10.0)"))
    assert(verilog.contains(".CLKFBOUT_MULT(8)"))
    assert(verilog.contains(".BANDWIDTH(\"OPTIMIZED\")"))
    assert(verilog.indexOf("CLKIN1_PERIOD") < verilog.indexOf("CLKFBOUT_MULT"))
    assert(verilog.contains(".CLKIN(clk)"))
    assert(verilog.contains(".RST(rst)"))
    assert(verilog.contains(".CLKOUT0("))
    assert(verilog.contains(".LOCKED("))
    assert(!verilog.contains("module PLLE2_BASE"))

  test("blackbox clock and reset ports never auto-connect"):
    class Top extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val pll = inst(new Pll(10.0, 8))

    val instance = (new Top).elaborateDesign().top.instances.head
    assert(!instance.connections.exists(_._1 == "CLKIN"))
    assert(!instance.connections.exists(_._1 == "RST"))

  test("declared output clock is usable and data output is CDC checked"):
    class Bad extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val pll = inst(new Pll(10.0, 8))
      pll.clkIn := clk
      pll.rst := rst
      val sampled = Reg(Bool())
      sampled := pll.locked

    val ex = intercept[ValidationFailed]((new Bad).elaborateDesign())
    assert(ex.diagnostics.exists(_.code == Code.CDC_UNSAFE_CROSSING))

    class Good extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val pll = inst(new Pll(10.0, 8))
      pll.clkIn := clk
      pll.rst := rst
      withClock(pll.out0Domain):
        val sampled = Reg(Bool())
        sampled := pll.locked
    assert((new Good).elaborateDesign().top.drivers.nonEmpty)

  test("missing output domain declaration is BLACKBOX_DOMAIN_REQUIRED"):
    class Undeclared extends BlackBox:
      override val moduleName = "BAD_IP"
      val q = Out(Bool()).named("Q")
    class Top extends Module:
      given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
      val bad = inst(new Undeclared)
    val ex = intercept[ValidationFailed](new Top)
    assert(ex.diagnostics.exists(_.code == Code.BLACKBOX_DOMAIN_REQUIRED))

  test("same fixed name with differing port signatures conflicts"):
    class First extends BlackBox:
      override val moduleName = "SAME_IP"
      val clk = Out(Clock()).named("CLK")
      val domain = newDomain(clk)
    class Second extends BlackBox:
      override val moduleName = "SAME_IP"
      val clk = Out(Clock()).named("OTHER_CLK")
      val domain = newDomain(clk)
    class Top extends Module:
      given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
      val a = inst(new First)
      val b = inst(new Second)
    val ex = intercept[ValidationFailed]((new Top).elaborateDesign())
    assert(ex.diagnostics.exists(_.code == Code.BLACKBOX_NAME_CONFLICT))

  test("same fixed name and identical signature is accepted"):
    class Clone extends BlackBox:
      override val moduleName = "CLONE_IP"
      val clk = Out(Clock()).named("CLK")
      val domain = newDomain(clk)
    class Top extends Module:
      given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
      val a = inst(new Clone)
      val b = inst(new Clone)
    val design = (new Top).elaborateDesign()
    assertEquals(design.modules.map(_.name), List("Top"))
    assertEquals(design.top.instances.map(_.target), List("CLONE_IP", "CLONE_IP"))

  // ---- R18.4 — a blackbox that joins a clock rather than creating one
  //
  // R18.2's `newDomain` is the PLL: an output clock unrelated to anything the
  // library already knows. The other shape — a `clk` *input* and data outputs
  // synchronous to it — is what every vendored peripheral is, and it had no
  // declaration at all: `outputDomain` would only take a domain `newDomain`
  // returned, and `newDomain` requires an output clock port.

  class Peripheral extends BlackBox:
    override val moduleName = "vendor_uart"
    val clk      = In(Clock()).named("clk")
    val rst      = In(Reset(ActiveHigh)).named("rst")
    val load     = In(UInt(8)).named("load")
    val readData = Out(UInt(8)).named("readData")
    val ready    = Out(Bool()).named("ready")
    outputDomain(readData, enclosingDomain)
    outputDomain(ready, enclosingDomain)

  test("an output in the enclosing domain is read in the parent without a crossing"):
    class Top extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val q   = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val uart = inst(new Peripheral)
      uart.clk  := clk
      uart.rst  := rst
      uart.load := 0.U(8)
      val latched = Reg(UInt(8), init = 0)
      when(uart.ready) { latched := uart.readData }   // <-- would be a crossing
      q := latched

    val design = (new Top).elaborateDesign()
    assertEquals(design.modules.map(_.name), List("Top"))
    val verilog = new Emission("test").emit(design, "Top").files.head._2
    assert(verilog.contains("vendor_uart "), verilog)
    assert(verilog.contains(".clk(clk)"), verilog)
    assert(verilog.contains(".readData("), verilog)
    assert(!verilog.contains("module vendor_uart"), verilog)

  test("the enclosing domain is the one in scope, so withClock places the box"):
    class Top extends Module:
      val fast = In(Clock())
      val slow = In(Clock())
      val rst  = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(fast, rst, ResetKind.Sync, ActiveHigh)
      val here = Reg(UInt(8), init = 0)
      withClock(ClockDomain(slow, rst, ResetKind.Sync, ActiveHigh)):
        val uart = inst(new Peripheral)
        uart.clk  := slow
        uart.rst  := rst
        uart.load := 0.U(8)
        here := uart.readData          // <-- `fast` reading a `slow` output

    val ex = intercept[ValidationFailed]((new Top).elaborateDesign())
    assert(ex.diagnostics.exists(_.code == Code.CDC_UNSAFE_CROSSING),
           ex.diagnostics.map(_.code).mkString(","))

  test("two instances of one box each bind their own enclosing domain"):
    class Top extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val q   = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val one = inst(new Peripheral)
      val two = inst(new Peripheral)
      for u <- List(one, two) do
        u.clk := clk; u.rst := rst; u.load := 0.U(8)
      q := one.readData + two.readData

    val design = (new Top).elaborateDesign()
    assertEquals(design.top.instances.map(_.name), List("one", "two"))
    assertEquals(design.top.instances.map(_.target), List("vendor_uart", "vendor_uart"))

  test("an output still has to be declared — enclosingDomain is a choice, not a default"):
    class Undeclared extends BlackBox:
      override val moduleName = "vendor_undeclared"
      val clk = In(Clock()).named("clk")
      val q   = Out(Bool()).named("q")
    class Top extends Module:
      val clk = In(Clock())
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      val bad = inst(new Undeclared)
    val ex = intercept[ValidationFailed](new Top)
    assert(ex.diagnostics.exists(_.code == Code.BLACKBOX_DOMAIN_REQUIRED))

  // ---- R18.5 — an external module's unpacked-array port ---------------------

  /** The shape that made this rule necessary: a vendored SCSI controller,
    * which declares `output reg [31:0] io_lba[DEVS]` and `output [7:0]
    * sd_buff_din[DEVS]` — one port each, carrying an array. */
  class ScsiTargets extends BlackBox:
    override val moduleName = "ncr5380"
    val clk        = In(Clock()).named("clk")
    val busCs      = In(Bool()).named("bus_cs")
    val ioLba      = Out(Vec(2, UInt(32))).named("io_lba")
    val ioRd       = Out(UInt(2)).named("io_rd")
    val sdBuffDin  = Out(Vec(2, UInt(8))).named("sd_buff_din")
    param("DEVS", 2)
    outputDomain(ioLba, enclosingDomain)
    outputDomain(ioRd, enclosingDomain)
    outputDomain(sdBuffDin, enclosingDomain)
    unpacked(ioLba)
    unpacked(sdBuffDin)

  test("an unpacked-array port connects whole, and its parent net is an array"):
    class Top extends Module:
      val clk = In(Clock())
      val sel = In(Bool())
      val lba = Out(UInt(32))
      val dat = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      val scsi = inst(new ScsiTargets)
      scsi.clk   := clk
      scsi.busCs := sel
      val which = Wire(UInt(1))
      which := Mux(sel, 1.U(1), 0.U(1))
      lba := scsi.ioLba(which)
      dat := scsi.sdBuffDin(0)

    val verilog = new Emission("test").emit((new Top).elaborateDesign(), "Top").files.head._2
    /** The single net one port is connected to, and that there is exactly one. */
    def connectedTo(port: String): String =
      val hits = s"""\\.$port\\((\\w+)\\)""".r.findAllMatchIn(verilog).toList
      assertEquals(hits.size, 1, s"connections to $port in:\n$verilog")
      hits.head.group(1)
    // One connection per array port, naming the parent's net array — and never
    // the per-element spelling R14.1 gives a generated child.
    val lbaNet = connectedTo("io_lba")
    val dinNet = connectedTo("sd_buff_din")
    assert(!verilog.contains(".io_lba_0("), verilog)
    assert(!verilog.contains(".sd_buff_din_0("), verilog)
    // The parent-side nets are unpacked arrays, which is what such a port has
    // to be connected to.
    assert(verilog.contains(s"wire [31:0] $lbaNet [0:1];"), verilog)
    assert(verilog.contains(s"wire [7:0] $dinNet [0:1];"), verilog)
    // A *packed* output of the same box still connects as one scalar.
    connectedTo("io_rd")

  test("a port that is not a Vec cannot be declared unpacked"):
    class Bad extends BlackBox:
      override val moduleName = "vendor_bad"
      val clk = In(Clock()).named("clk")
      val q   = Out(UInt(8)).named("q")
      outputDomain(q, enclosingDomain)
      unpacked(q)
    val ex = intercept[IllegalArgumentException](new Bad)
    assert(ex.getMessage.contains("unpacked requires a Vec port"), ex.getMessage)

  test("an unpacked-array port cannot be connected to a port of the parent"):
    // R14.1 spells a *port* Vec as one net per element, so there is no single
    // name for the array — the connection has to be an internal signal.
    class Sink extends BlackBox:
      override val moduleName = "vendor_sink"
      val clk = In(Clock()).named("clk")
      val xs  = In(Vec(2, UInt(8))).named("xs")
      unpacked(xs)
    class Top extends Module:
      val clk = In(Clock())
      val xs  = In(Vec(2, UInt(8)))
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      val u = inst(new Sink)
      u.clk := clk
      u.xs  := xs
    val ex = intercept[Exception](new Emission("test").emit((new Top).elaborateDesign(), "Top"))
    assert(ex.getMessage.contains("unpacked-array port"), ex.getMessage)

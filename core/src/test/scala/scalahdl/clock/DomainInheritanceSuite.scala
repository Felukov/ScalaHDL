package scalahdl.clock

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.emit.VerilogEmitter

/** R13.2 — Domain inheritance on instantiation. `withClock(domain) { val u =
  * inst(new Child()) }` auto-connects the child's clock/reset from the
  * domain. Each test maps to a checkbox in `roadmap/open/M04-scalar-types/README.md`. */
class DomainInheritanceSuite extends munit.FunSuite:

  // A child module with clock/reset ports and a register.
  class SlowUnit extends Module:
    val clk   = In(Clock())
    val rst_n = In(Reset(ActiveLow))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
    val q = Out(UInt(8))
    val cnt = Reg(UInt(8), init = 0.U(8))
    cnt := cnt + 1.U(8)
    q := cnt

  test("withClock(slow) { inst(new SlowUnit()) } wires clk/rst from slow"):
    class Parent extends Module:
      val fastClk   = In(Clock())
      val fastRst_n = In(Reset(ActiveLow))
      val slowClk   = In(Clock())
      val slowRst_n = In(Reset(ActiveLow))
      given defaultDomain: ClockDomain = ClockDomain(fastClk, fastRst_n, ResetKind.Async, ActiveLow)
      withClock(ClockDomain(slowClk, slowRst_n, ResetKind.Async, ActiveLow)):
        val u = inst(new SlowUnit())
    val design = (new Parent).elaborateDesign()
    // The instance should be in the IR
    assert(design.top.instances.nonEmpty, s"should have an instance: ${design.top.instances}")
    val inst0 = design.top.instances.head
    assert(inst0.name == "u", s"instance name should be 'u': ${inst0.name}")
    // Check auto-connections: clk → slowClk, rst_n → slowRst_n
    val connMap = inst0.connections.toMap
    assert(connMap.contains("clk"), s"should auto-connect clk: ${inst0.connections}")
    assert(connMap.contains("rst_n"), s"should auto-connect rst_n: ${inst0.connections}")
    // The clk connection should reference slowClk (id matches)
    val slowClkId = design.top.signals.find(_.name == "slowClk").map(_.id)
    val clkConn = connMap("clk")
    assert(clkConn == Expr.Ref(slowClkId.get),
      s"clk should connect to slowClk: $clkConn")

  test("a child with no clock/reset ports is unaffected"):
    class NoClkChild extends Module:
      val a = In(UInt(8))
      val q = Out(UInt(8))
      q := a
    class Parent extends Module:
      val clk   = In(Clock())
      val rst_n = In(Reset(ActiveLow))
      val x     = In(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
      val child = inst(new NoClkChild())
      child.a := x   // R23 — a data input still has to be connected
    val design = (new Parent).elaborateDesign()
    val inst0 = design.top.instances.head
    // No clock/reset auto-connections since the child has none
    assert(!inst0.connections.exists(_._1 == "clk"),
      s"no spurious clk connection: ${inst0.connections}")
    assert(!inst0.connections.exists(_._1 == "rst_n"),
      s"no spurious rst_n connection: ${inst0.connections}")

  test("instance emits as a Verilog module instantiation"):
    class Parent extends Module:
      val fastClk   = In(Clock())
      val fastRst_n = In(Reset(ActiveLow))
      val slowClk   = In(Clock())
      val slowRst_n = In(Reset(ActiveLow))
      given defaultDomain: ClockDomain = ClockDomain(fastClk, fastRst_n, ResetKind.Async, ActiveLow)
      withClock(ClockDomain(slowClk, slowRst_n, ResetKind.Async, ActiveLow)):
        val u = inst(new SlowUnit())
    val design = (new Parent).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Parent")
    assert(v.contains("SlowUnit u("),
      s"should emit module instantiation: $v")
    assert(v.contains(".clk(slowClk)"),
      s"should connect .clk(slowClk): $v")
    assert(v.contains(".rst_n(slowRst_n)"),
      s"should connect .rst_n(slowRst_n): $v")

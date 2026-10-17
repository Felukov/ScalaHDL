package scalahdsl.aggregates

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.VerilogEmitter

/** Smoke test for the M05 Bundle DSL: a bundle at a port, field access as a
  * real symbol, emission of per-leaf port names. */
class BundleSmokeSuite extends munit.FunSuite:

  case class Req(w: Int) extends Bundle:
    val addr = UInt(w)
    val prot = Bits(3)

  test("In(Req(8)) binds leaves; req.addr resolves as a DslSignal sub-handle"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val req = In(Req(8))
      val q = Out(UInt(8))
      q := req.addr
    val design = (new Top).elaborateDesign()
    val port = design.top.ports.find(_.name == "req").getOrElse(fail("no req port"))
    assert(port.typeOf.isInstanceOf[Type.Bundle], s"req should be a Bundle: ${port.typeOf}")
    val fields = port.typeOf.asInstanceOf[Type.Bundle].fields.map(_._1)
    assertEquals(fields, List("addr", "prot"), s"field names should be addr,prot: $fields")
    // The field access `req.addr` resolves as a real symbol and emits the
    // leaf name `req_addr` in the assignment expression.
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Top")
    assert(v.contains("req_addr"), s"should emit req_addr in the expression: $v")
    // R07.1: the bundle port expands to per-leaf port lines.
    assert(v.contains("req_addr"), s"port should expand to req_addr: $v")
    assert(v.contains("req_prot"), s"port should expand to req_prot: $v")

  test("internal Vec of UInt lowers to struct-of-arrays; port Vec to per-element"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val i = In(UInt(2))
      // internal Vec → struct-of-arrays: entries [0:3]
      val entries = Reg(Vec(4, UInt(8)), init = null)
      val rd = Out(UInt(8))
      // runtime-index read on internal Vec → entries[i] (no mux)
      rd := entries(i)
    val design = (new Top).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Top")
    // R14.1: internal Vec → one net array, `reg [7:0] entries [0:3];`
    assert(v.contains("reg [7:0] entries [0:3];"), s"internal Vec should be struct-of-arrays: $v")
    // R14.1: runtime-index read → entries[i] (no mux)
    assert(v.contains("entries["), s"runtime-index read should be entries[i]: $v")
    assert(!v.contains("Mux") && !v.contains("mux"), s"no generated mux: $v")

  case class Entry(w: Int) extends Bundle:
    val addr = UInt(w)
    val prot = Bits(3)

  test("Vec bundle factory runs once per element and typed indexing preserves fields"):
    var constructions = 0
    def fresh(): Entry =
      constructions += 1
      Entry(8)
    val blueprint = Vec(4)(fresh())
    assertEquals(constructions, 4, "R14 requires one fresh Bundle construction per Vec element")

    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val entries = Reg(blueprint, init = null)
      val q = Out(UInt(8))
      q := entries(2).addr
    val design = (new Top).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Top")
    assert(v.contains("q = entries_addr[2]"), s"typed entries(2).addr must preserve the Vec index path: $v")

  test("arbitrarily nested Vec dimensions lower recursively and preserve path order"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val i = In(UInt(1)); val j = In(UInt(2))
      val matrix = Reg(Vec(2, Vec(4, UInt(8))), init = null)
      val q = Out(UInt(8))
      q := matrix(i)(j)
    val design = (new Top).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Top")
    assert(v.contains("reg [7:0] matrix [0:1] [0:3];"), s"all Vec dimensions must be retained: $v")
    assert(v.contains("q = matrix[i][j]"), s"nested runtime indices must retain order: $v")

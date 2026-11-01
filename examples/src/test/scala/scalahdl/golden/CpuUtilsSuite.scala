package scalahdl.golden

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission
import scalahdl.examples.{AddressSlot, CpuBankMap, CpuRequest, CpuResponse,
  CpuScratchMap, CpuUtils}

/** R1/R3/R26 — the address map as a pair of library `def`s: what a helper that
  * declares can promise, and what one that only drives can promise instead.
  *
  * What this suite asserts is what the example promises: the same two helpers
  * build two designs of different widths and counts, each emitting one file per
  * module; a declaring helper called twice is a name collision; a driving helper
  * called twice is fine; and the helper's preconditions reject a malformed map.
  * The emitted construction bytes are the emitter's contract, pinned by `core`'s
  * `InstanceArrayBundles` golden. */
class CpuUtilsSuite extends munit.FunSuite:

  private def emit(m: Module, name: String): Map[String, String] =
    new Emission("0.1.1").emit(m.elaborateDesign(), name).files.toMap

  private val scratch = emit(new CpuScratchMap, "scalahdl.examples.CpuScratchMap")
  private val banks   = emit(new CpuBankMap, "scalahdl.examples.CpuBankMap")

  test("the same two helpers build two designs of different widths and counts"):
    assertEquals(scratch.keys.toSet, Set("CpuScratchpad_a2_d32.v", "CpuScratchMap_n4.v"))
    assertEquals(banks.keys.toSet, Set("CpuScratchpad_a3_d16.v", "CpuBankMap_n2.v"))

  // ---- the two halves of the rule, pinned ----

  private val twoSlots = List(
    AddressSlot(base = 0x00, mask = 0xfc),
    AddressSlot(base = 0x04, mask = 0xfc))

  /** A declaring helper names its own output, so calling it twice declares the
    * same name twice. */
  class RoutesTwice extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    val req = In(CpuRequest(8, 32))
    given ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
    val first  = CpuUtils.route(req, twoSlots, 2)
    val second = CpuUtils.route(req, twoSlots, 2)

  test("calling a declaring helper twice is a name collision, not a mangling"):
    val error = intercept[ValidationFailed]((new RoutesTwice).elaborateDesign())
    assert(error.diagnostics.map(_.code).contains(Code.NAME_COLLISION),
      error.diagnostics.map(_.code).toString)

  /** A driving helper declares nothing, so it has no name to collide with —
    * only the targets must differ, which is R12's one-signal-one-block rule
    * and has nothing to do with the helper. */
  class SelectsTwice extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    val req = In(CpuRequest(8, 32))
    val resp = Out(CpuResponse(32))
    val mon  = Out(CpuResponse(32))
    given ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
    val fanout    = CpuUtils.route(req, twoSlots, 2)
    val responses = Wire(Vec(twoSlots.length, CpuResponse(32)))
    val slot = instances(twoSlots.length)(i => new scalahdl.examples.CpuScratchpad(2, 32))
    for i <- twoSlots.indices do
      slot(i).req := fanout(i)
      responses(i) := slot(i).resp
    CpuUtils.select(resp, responses, twoSlots.length)
    CpuUtils.select(mon, responses, twoSlots.length)

  test("calling a driving helper twice is fine"):
    val design = (new SelectsTwice).elaborateDesign()
    assertEquals(design.top.instances.map(_.name), List("slot_0", "slot_1"))

  // ---- the helper's preconditions ----

  test("a mask that does not leave the slot address free is rejected"):
    val error = intercept[IllegalArgumentException](
      new CpuScratchMap(List(AddressSlot(base = 0x00, mask = 0xf0))).elaborateDesign())
    assert(error.getMessage.contains("leaves 0xf free"), error.getMessage)

  test("a base that is not slot-aligned is rejected"):
    val error = intercept[IllegalArgumentException](
      new CpuScratchMap(List(AddressSlot(base = 0x02, mask = 0xfc))).elaborateDesign())
    assert(error.getMessage.contains("not aligned"), error.getMessage)

  test("an overlapping map is still decided at elaboration, not at runtime"):
    // Two entries with the same cube: the helper's own preconditions pass
    // (both masks leave three bits free, both bases are slot-aligned), so what
    // rejects this is R26 reading the `parallelCase` the helper opened.
    val overlapping = List(
      AddressSlot(base = 0x1000, mask = 0xfff8),
      AddressSlot(base = 0x1000, mask = 0xfff8))
    val error = intercept[ValidationFailed](new CpuBankMap(overlapping).elaborateDesign())
    assertEquals(error.diagnostics.map(_.code), List(Code.SELECT_ARMS_OVERLAP))

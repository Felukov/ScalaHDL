package scalahdl.golden

import scalahdl.emit.{Emission, Vendor}
import scalahdl.examples.SafeClockCrossing

/** R05.1 / R20 — the safe CDC example's file-level contract.
  *
  * What stays here is what the example *promises*: the design emits one Verilog
  * file and its reports, and it emits them deterministically. The CDC report's
  * content is asserted in `core`, on a `core`-local design (R39); the emitted
  * Verilog's exact bytes are the emitter's contract and are pinned by the `core`
  * corpus, not here. */
final class SafeClockCrossingGoldenSuite extends munit.FunSuite:
  private val version = "0.1.2"
  private val generator = "scalahdl.examples.SafeClockCrossing"

  private def emitted() =
    val design = (new SafeClockCrossing).elaborateDesign()
    new Emission(version, Some(Vendor.Xilinx)).emit(design, generator)

  test("the design emits one module and its reports, deterministically"):
    val first = emitted()
    assertEquals(first.files.map(_._1), List("SafeClockCrossing.v"))
    assertEquals(first.reports.map(_._1),
      List("SafeClockCrossing.cdc.rpt", "SafeClockCrossing.levels.rpt",
           "design.levels.rpt"))
    assertEquals(first, emitted())

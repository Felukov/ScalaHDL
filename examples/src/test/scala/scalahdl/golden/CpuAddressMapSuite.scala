package scalahdl.golden

import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.emit.Emission
import scalahdl.examples.{AddressSlot, CpuAddressMap}

/** R16/R14/R26 — the address-map example: an instance array, a `Vec` of
  * bundles carrying each unit's bus, and a decoder whose disjointness is
  * decided at elaboration.
  *
  * What this suite asserts is what the example promises: it emits one file per
  * module (the units come from one blueprint, so three slots yield one unit
  * module), and an overlapping map is an elaboration error rather than a race.
  * The emitted construction bytes — the net arrays, the cube arms, the
  * `requests_addr[i]` connections — are the emitter's contract and are pinned
  * by `core`'s `InstanceArrayBundles` golden. */
class CpuAddressMapSuite extends munit.FunSuite:

  private val emitted = new Emission("0.1.2")
    .emit((new CpuAddressMap).elaborateDesign(), "scalahdl.examples.CpuAddressMap")

  test("three map entries instantiate three units of one emitted module"):
    assertEquals(emitted.files.map(_._1), List("CpuScratchpad_a2_d32.v", "CpuAddressMap_n3.v"))
    val design = (new CpuAddressMap).elaborateDesign()
    assertEquals(design.top.instances.map(i => i.name -> i.target), List(
      "slot_0" -> "CpuScratchpad_a2_d32",
      "slot_1" -> "CpuScratchpad_a2_d32",
      "slot_2" -> "CpuScratchpad_a2_d32"
    ))

  test("an overlapping address map is an elaboration error, not a race"):
    val overlapping = List(
      AddressSlot(base = 0x00, mask = 0xfc),
      AddressSlot(base = 0x02, mask = 0xfc)   // same cube as 0x00 under this mask
    )
    val error = intercept[ValidationFailed](new CpuAddressMap(overlapping).elaborateDesign())
    assertEquals(error.diagnostics.map(_.code), List(Code.SELECT_ARMS_OVERLAP))

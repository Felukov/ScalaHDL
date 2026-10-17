package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, param}
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

/** Four four-word scratchpads filling the bottom of an 8-bit bus. */
object CpuScratchMap:
  val defaultMap: List[AddressSlot] = List(
    AddressSlot(base = 0x00, mask = 0xfc),
    AddressSlot(base = 0x04, mask = 0xfc),
    AddressSlot(base = 0x08, mask = 0xfc),
    AddressSlot(base = 0x0c, mask = 0xfc)
  )

/** Two eight-word banks at 0x1000 in a 16-bit space. Nothing about the map is
  * like [[CpuScratchMap]]'s: wider bus, narrower data, bigger slots, and a
  * base that is not zero, so every cube compares against a real value. */
object CpuBankMap:
  val defaultMap: List[AddressSlot] = List(
    AddressSlot(base = 0x1000, mask = 0xfff8),
    AddressSlot(base = 0x1008, mask = 0xfff8)
  )

/** [[CpuAddressMap]] with its two decode blocks lifted into [[CpuUtils]], and
  * a second design, [[CpuBankMap]], built from the same two `def`s.
  *
  * The point of the pair is that a generator is a *function*, and the way to
  * reuse one is to call it — not to inherit from a base module, not to
  * parameterise one class until it covers both cases. `CpuScratchMap` and
  * `CpuBankMap` share no supertype beyond `Module` and no parameter; they
  * agree only on the bus bundles, and that is enough for the same fan-out and
  * the same selection to serve both at different widths and different slot
  * counts.
  *
  * What each module keeps for itself is the part R1 makes it keep. The
  * response `Vec` is declared *here*, so it is named here — `responses`, from
  * this file's `val` — while the request `Vec` is declared inside
  * [[CpuUtils.route]] and arrives already named `requests`. Read the emitted
  * Verilog and the two are indistinguishable; read the Scala and the boundary
  * is exactly the line between what a helper declares and what it only drives.
  *
  * The rest is [[CpuAddressMap]]'s hierarchy unchanged: an instance array
  * (R16) whose count comes from the map, and aggregate ports connected whole
  * (R23), leaf against leaf in the output. */
class CpuScratchMap(map: List[AddressSlot])
    extends Module(param("n", map.length), param("map", map)):
  def this() = this(CpuScratchMap.defaultMap)

  private val addrWidth = 8
  private val dataWidth = 32
  private val slotAddrWidth = 2

  val clk  = In(Clock())
  val rst  = In(Reset(ActiveHigh))
  val req  = In(CpuRequest(addrWidth, dataWidth))
  val resp = Out(CpuResponse(dataWidth))

  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  // One call, and the fan-out, the defaults and the address decode are done.
  // Deliberately bound to a `val` whose name is *not* the emitted one: R1
  // captures the `val` inside `route`, so these nets are `requests_*` in the
  // Verilog no matter what this line calls them.
  val fanout = CpuUtils.route(req, map, slotAddrWidth)

  // This one is declared here, so this `val` is the emitted name.
  val responses = Wire(Vec(map.length, CpuResponse(dataWidth)))

  val slot = instances(map.length)(i => new CpuScratchpad(slotAddrWidth, dataWidth))
  for i <- map.indices do
    slot(i).req := fanout(i)
    responses(i) := slot(i).resp

  CpuUtils.select(resp, responses, map.length)

/** The second caller. Same two helper calls, nothing else in common with
  * [[CpuScratchMap]]: a 16-bit address, 16-bit data, eight-word slots, and a
  * map that starts at 0x1000. */
class CpuBankMap(map: List[AddressSlot])
    extends Module(param("n", map.length), param("map", map)):
  def this() = this(CpuBankMap.defaultMap)

  private val addrWidth = 16
  private val dataWidth = 16
  private val slotAddrWidth = 3

  val clk  = In(Clock())
  val rst  = In(Reset(ActiveHigh))
  val req  = In(CpuRequest(addrWidth, dataWidth))
  val resp = Out(CpuResponse(dataWidth))

  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val fanout    = CpuUtils.route(req, map, slotAddrWidth)
  val responses = Wire(Vec(map.length, CpuResponse(dataWidth)))

  val bank = instances(map.length)(i => new CpuScratchpad(slotAddrWidth, dataWidth))
  for i <- map.indices do
    bank(i).req := fanout(i)
    responses(i) := bank(i).resp

  CpuUtils.select(resp, responses, map.length)

package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, param}
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

/** One entry of an address map: every address whose masked value equals `base`
  * belongs to this slot. A mask of `0xfc` over an 8-bit bus leaves the low two
  * bits to the slot itself, so each entry claims four words. */
final case class AddressSlot(base: Int, mask: Int)

object CpuAddressMap:
  /** Three four-word scratchpads at 0x00, 0x04 and 0x08. The list is the only
    * place the number of units is stated: it decides how many are
    * instantiated, how wide the request and response Vecs are, and how many
    * arms the decoder has. */
  val defaultMap: List[AddressSlot] = List(
    AddressSlot(base = 0x00, mask = 0xfc),
    AddressSlot(base = 0x04, mask = 0xfc),
    AddressSlot(base = 0x08, mask = 0xfc)
  )

/** [[CpuBridge]] with an address map in front of it: one `CpuScratchpad` per
  * map entry, the request routed to the entry whose cube the address falls in,
  * and that entry's response returned to the bus.
  *
  * Three things this example is here to show:
  *
  *   - **An instance array (R16).** `instances(n)(...)` declares as many
  *     children as the map has entries, named `slot_0` … `slot_{n-1}`. The
  *     count is a value, not a number of `val`s.
  *   - **A `Vec` of bundles as the fan-out (R14/R07).** The parent cannot
  *     assign a field of a child's input port — that field belongs to the
  *     child (R23) — so it builds each request in its own `Vec` of
  *     `CpuRequest` and connects the element whole. The responses come back
  *     the same way.
  *   - **The map's disjointness is checked, not asserted (R26).** Each arm of
  *     the `parallelCase` is a cube `(addr & mask) === base`, and R26 rejects
  *     two entries that overlap, naming both and an address that reaches them
  *     both. An address map that claims to be a partition is exactly the claim
  *     `parallelCase` exists to decide.
  *
  * The map is a non-name-contributing parameter (R16.1): it is compared for
  * module identity but the emitted name carries only the entry count —
  * `CpuAddressMap_n3`. */
class CpuAddressMap(map: List[AddressSlot])
    extends Module(param("n", map.length), param("map", map)):
  def this() = this(CpuAddressMap.defaultMap)

  require(map.nonEmpty, "an address map needs at least one entry")

  /** The bus is 8-bit addressed; a slot's own address is the low two bits,
    * which is what its mask leaves free. */
  private val addrWidth = 8
  private val dataWidth = 32
  private val slotAddrWidth = 2

  val clk  = In(Clock())
  val rst  = In(Reset(ActiveHigh))
  val req  = In(CpuRequest(addrWidth, dataWidth))
  val resp = Out(CpuResponse(dataWidth))

  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  // The parent's side of each unit's bus. R14.1 — an internal Vec is a
  // struct-of-arrays, so these are `requests_addr [0:2]` and friends.
  val requests  = Wire(Vec(map.length, CpuRequest(slotAddrWidth, dataWidth)))
  val responses = Wire(Vec(map.length, CpuResponse(dataWidth)))

  val slot = instances(map.length)(i => new CpuScratchpad(slotAddrWidth, dataWidth))
  for i <- map.indices do
    slot(i).req := requests(i)
    responses(i) := slot(i).resp

  // Every unit sees the address and the write data unconditionally; what the
  // decoder decides is which one is told to act. Driving the whole Vec here
  // and refining below is R11's rule read forwards: assign unconditionally,
  // then narrow.
  for i <- map.indices do
    requests(i).we   := False
    requests(i).re   := False
    requests(i).addr := req.addr.resize(slotAddrWidth)
    requests(i).data := req.data

  // R26 — one arm per map entry, each a cube over the bus address. Two entries
  // whose ranges overlapped would be an elaboration error here rather than a
  // design that works until two units answer the same address.
  parallelCase:
    for (entry, i) <- map.zipWithIndex do
      on((req.addr & entry.mask.U(addrWidth)) === entry.base.U(addrWidth)):
        requests(i).we := req.we
        requests(i).re := req.re

  // Only the addressed unit was given `we`/`re`, so at most one `ack` can be
  // high; the selection needs no held copy of the decode to return the answer
  // a cycle later. `priorityCase` claims nothing about that — the mutual
  // exclusion is a consequence of the routing above, not a cube R26 could
  // read — and an unmapped address falls through to the default: no ack.
  priorityCase:
    for i <- map.indices do
      on(responses(i).ack):
        resp.ack  := True
        resp.data := responses(i).data
    default:
      resp.ack  := False
      resp.data := 0

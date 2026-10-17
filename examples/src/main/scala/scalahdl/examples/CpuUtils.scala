package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module

/** Design-level helpers over the [[CpuRequest]] / [[CpuResponse]] bus: the
  * address-map fan-out and the response selection that [[CpuAddressMap]]
  * writes out by hand, lifted into ordinary Scala `def`s.
  *
  * There is no second surface for library code. `Wire`, `parallelCase`, `on`
  * and `:=` mean the same thing here as in a module body, and R3 is what makes
  * that true: the elaboration context is a *value* threaded through a `using`
  * parameter, not ambient state, so a helper's signature says both that it
  * builds hardware and whose module it builds into. A helper cannot silently
  * construct into the wrong module, and two modules elaborated in one process
  * cannot see each other's builder.
  *
  * The two helpers differ in one respect, and that difference is the whole
  * lesson of this file:
  *
  *   - [[route]] **declares** the `Vec` it returns. Under R1 the emitted nets
  *     take the name of the `val` inside *this* method — `requests` — not the
  *     name the caller binds the result to. The library names those nets.
  *   - [[select]] **declares nothing**. It drives a target the caller already
  *     owns, so it contributes no name at all.
  *
  * So `route` may be called once per module and `select` as often as you like.
  * Two `route` calls would declare two signals named `requests`, and R1 makes
  * that a `NAME_COLLISION` rather than quietly mangling one to `requests_1` —
  * an emitted name is the `val` name, unmangled, or it is an error. Reuse of a
  * *declaring* helper is therefore across modules, which is what
  * [[CpuScratchMap]] and [[CpuBankMap]] show.
  *
  * The cost is visible in the diagnostic and worth stating: because the name
  * comes from this file, the collision is reported against this file's `val
  * requests` line — twice — rather than against the two call sites that
  * actually differ. R1 buys an unmangled emitted name at the price of a helper
  * that names its own output. */
object CpuUtils:

  /** Fan `req` out to one slot per map entry and hand back the parent's side
    * of every slot's request bus.
    *
    * Address and write data reach every slot unconditionally; what the decoder
    * decides is which slot is told to *act*. That is R11's rule read forwards
    * — drive the whole `Vec` first, then narrow — and it is why `we`/`re` are
    * assigned twice here without either assignment being dead.
    *
    * R26 — one `parallelCase` arm per entry, each a cube over the bus address.
    * The claim that the map is a partition is decided at elaboration: two
    * entries whose ranges overlap are an error naming both and an address that
    * reaches them both, not a design that works until two slots answer at
    * once.
    *
    * Everything about the shape is read off the arguments — the slot count
    * from `map`, the bus widths from `req`'s own constructor parameters — so
    * the same `def` serves any width and any number of slots. `CpuRequest` is
    * a `case class`, so `req.addrWidth` is Scala data sitting beside `req.addr`
    * the hardware signal, and neither needs the other's spelling.
    *
    * @param req           the bus request to route, in the caller's module
    * @param map           the address map; one slot is produced per entry
    * @param slotAddrWidth width of a slot's own address, the bits every mask
    *                      leaves free
    * @return the parent's side of each slot's request bus, indexed by map
    *         entry. Its emitted nets are named `requests_*` (see above).
    */
  def route(req: CpuRequest, map: List[AddressSlot], slotAddrWidth: Int)(
      using m: Module, cd: ClockDomain): BundleVecSignal[CpuRequest] =
    require(map.nonEmpty, "an address map needs at least one entry")
    require(slotAddrWidth >= 1 && slotAddrWidth < req.addrWidth,
      s"a slot address of $slotAddrWidth bits does not fit inside a " +
        s"${req.addrWidth}-bit bus address")

    // The library states its precondition in Scala, at elaboration, where the
    // message can name the offending entry. A mask that left a different
    // number of bits free than `slotAddrWidth` would still elaborate and still
    // emit — it would just decode a range the slot cannot address.
    val slotBits = (1 << slotAddrWidth) - 1
    val busBits  = (1 << req.addrWidth) - 1
    for entry <- map do
      require(((~entry.mask) & busBits) == slotBits,
        f"map entry at base 0x${entry.base}%x has mask 0x${entry.mask}%x, which " +
          f"leaves 0x${(~entry.mask) & busBits}%x free rather than the low " +
          s"$slotAddrWidth bits a slot addresses")
      require((entry.base & slotBits) == 0,
        f"map entry base 0x${entry.base}%x is not aligned to the " +
          s"${1 << slotAddrWidth}-word slot it opens")

    // R14 — a `Wire` takes every shape a `Reg` does, so the fan-out is a Vec
    // of bundles. R14.1: internal, therefore a struct-of-arrays —
    // `requests_addr [0:n-1]` and one array per remaining field.
    val requests = Wire(Vec(map.length, CpuRequest(slotAddrWidth, req.dataWidth)))

    for i <- map.indices do
      requests(i).we   := False
      requests(i).re   := False
      requests(i).addr := req.addr.resize(slotAddrWidth)
      requests(i).data := req.data

    // R26 — the arm's body is an elaboration scope, not a lexical one, so an
    // ordinary `for` may enlist the alternatives and a helper may open the
    // construct at all. That is the same property `is` has under R22, and it
    // is what lets a decoder be a `def` over a `List` instead of a written-out
    // chain whose length is fixed in the source.
    parallelCase:
      for (entry, i) <- map.zipWithIndex do
        on((req.addr & entry.mask.U(req.addrWidth)) === entry.base.U(req.addrWidth)):
          requests(i).we := req.we
          requests(i).re := req.re

    requests

  /** Return the addressed slot's answer on `resp`.
    *
    * `priorityCase` claims nothing — the first arm that holds wins — and that
    * is the honest construct here even though at most one `ack` can be high.
    * The mutual exclusion is a *consequence* of [[route]] having given `we`/`re`
    * to one slot only; it is not a cube over a common selector, so R26 could
    * not decide it and `parallelCase` would be a claim made where no check
    * exists. An unmapped address reaches no slot, no slot acks, and the
    * `default` arm answers.
    *
    * This helper declares nothing: `resp` and `responses` both belong to the
    * caller, so it contributes no name and may be called any number of times
    * in one module. It needs no `ClockDomain` either — a domain is what a
    * *declaration* is recorded against (R4), and there is no declaration here.
    *
    * @param resp      the response to drive, in the caller's module
    * @param responses each slot's answer, indexed as `map` is
    * @param count     the number of slots; `BundleVecSignal` does not publish
    *                  its own size, so the caller passes `map.length`
    */
  def select(resp: CpuResponse, responses: BundleVecSignal[CpuResponse],
             count: Int)(using m: Module): Unit =
    require(count >= 1, "a response selection needs at least one slot")
    priorityCase:
      for i <- 0 until count do
        on(responses(i).ack):
          resp.ack  := True
          resp.data := responses(i).data
      default:
        resp.ack  := False
        resp.data := 0

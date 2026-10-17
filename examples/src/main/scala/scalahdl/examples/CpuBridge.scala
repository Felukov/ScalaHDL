package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, param}
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

/** The request half of a small CPU bus: what a master asks for in one cycle.
  *
  * R07 — a bundle carries no direction, so this one blueprint serves both ends
  * of the hierarchy: the parent declares `In(CpuRequest(...))` and the child
  * declares `In(CpuRequest(...))`, and each end's declaration says which way
  * every field travels. */
final case class CpuRequest(addrWidth: Int, dataWidth: Int) extends Bundle:
  val we   = Bool()
  val re   = Bool()
  val addr = UInt(addrWidth)
  val data = UInt(dataWidth)

/** The answer half of the same bus, declared by the module that produces it. */
final case class CpuResponse(dataWidth: Int) extends Bundle:
  val ack  = Bool()
  val data = UInt(dataWidth)

/** The nested module: a small scratchpad that answers a request one cycle
  * later. It receives the request its parent hands down and declares the
  * response bundle it sends back up.
  *
  * The response is *built here* — `ack` and `data` are this module's own
  * registers, assigned leaf by leaf into the output bundle — because the
  * fields have different logic. What travels whole is the port, not the
  * assignment. */
class CpuScratchpad(addrWidth: Int, dataWidth: Int)
    extends Module(param("a", addrWidth), param("d", dataWidth)):
  val clk  = In(Clock())
  val rst  = In(Reset(ActiveHigh))
  val req  = In(CpuRequest(addrWidth, dataWidth))
  val resp = Out(CpuResponse(dataWidth))

  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  // R14.1 — an internal Vec is a net array, so the request's address indexes it
  // natively; R14.2 — the runtime-index write is legal because it is a `Reg`.
  val cells = Reg(Vec(1 << addrWidth, UInt(dataWidth)), init = null)
  when(req.we):
    cells(req.addr) := req.data

  val ack = Reg(Bool(), init = False)
  ack := req.we | req.re

  val readData = Reg(UInt(dataWidth), init = 0)
  when(req.re):
    readData := cells(req.addr)

  resp.ack  := ack
  resp.data := readData

/** The parent: it owns the bus ports, hands its request down to the scratchpad
  * and returns the scratchpad's response back up.
  *
  * Both crossings are written *whole* here — the bundle, not its fields — which
  * is what a parent that already has the whole request wants to write:
  * `scratchpad.req := req` is the connection and `resp := scratchpad.resp` is
  * the return. A bundle is a signal and a reference to one wherever either is
  * expected, so neither crossing names an accessor.
  *
  * A parent that assembles the request from parts writes the pins instead, and
  * that is the same kind of statement rather than a different one:
  *
  * {{{
  *   scratchpad.req.we   := writeStrobe
  *   scratchpad.req.re   := readStrobe
  *   scratchpad.req.addr := latchedAddr
  *   scratchpad.req.data := latchedData
  * }}}
  *
  * The Verilog-2001 output is why the two spellings meet: the boundary has no
  * aggregate to carry, so the instantiation reads leaf against leaf either way
  * — `.req_addr(req_addr)`, `.resp_data(scratchpad_resp_data)`. The `_` names
  * are the field names; no packing or unpacking happens at the boundary, and a
  * pin connection puts its expression straight on the pin.
  *
  * `clk` and `rst` are not connected here: R13.2 inherits them from the
  * enclosing domain. */
class CpuBridge extends Module:
  val clk  = In(Clock())
  val rst  = In(Reset(ActiveHigh))
  val req  = In(CpuRequest(2, 32))
  val resp = Out(CpuResponse(32))

  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val scratchpad = inst(new CpuScratchpad(2, 32))
  scratchpad.req := req
  resp := scratchpad.resp

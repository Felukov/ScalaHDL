package scalahdl.examples

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module

/** The port group of [[FlowExample]], written once from the module's side.
  *
  * This is the ScalaHDL analogue of SpinalHDL's `val io = new Bundle { ... }`:
  * the group lives under one `val`, so `io.request` and `io.answer` are ports
  * while `storage` is internal, and the emitted names keep the group as a
  * prefix: `io_request_valid`, `io_answer_payload`, and so on.
  *
  * An `Interface` (R31) rather than a `Bundle` (R7) is what carries a
  * direction. A bundle's fields are deliberately direction-free — one
  * direction is a fact about the declaration, applied uniformly at the port —
  * so a bundle cannot hold a request going in and an answer going out. An
  * interface writes both from one viewpoint (here the module's), and `Slave`
  * / `Master` name the side each nested endpoint takes.
  *
  * {{{
  *   val request = Slave(Flow(UInt(8)))   // valid, payload: in
  *   val answer  = Master(Flow(UInt(8)))  // valid, payload: out
  * }}} */
final case class FlowExampleIO() extends Interface:
  /** In — the module receives a request. */
  val request = Slave(Flow(UInt(8)))
  /** Out — the module produces an answer. */
  val answer = Master(Flow(UInt(8)))

object FlowState extends Encoding(1):
  val Idle, SendEcho = Value

/** The SpinalHDL `FlowExample`, with its ports grouped under `io`.
  *
  * SpinalHDL writes the group as
  *
  * {{{
  *   val io = new Bundle {
  *     val request = slave(Flow(Bits(8 bit)))
  *     val answer  = master(Flow(Bits(8 bit)))
  *   }
  * }}}
  *
  * and ScalaHDL writes it as the [[FlowExampleIO]] declaration above plus
  * `val io = Master(FlowExampleIO())` here. The two spellings produce the same
  * ports — `io_request_valid`, `io_request_payload`, `io_answer_valid`,
  * `io_answer_payload` — and the same references, `io.request.valid` and
  * `io.answer.payload`. Grouping is the whole point: a reader can tell the
  * ports from the register below at a glance without reading each declaration.
  *
  * The body is the state machine from the SpinalHDL excerpt. `Flow` has no
  * `ready`, so the request is taken on the clock it arrives; `SendEcho`
  * raises the answer for exactly one clock. R11 wants the combinational
  * `answer.valid` driven on every path, which is the default before the
  * machine — SpinalHDL's `io.answer.setIdle()` in a different spelling. The
  * payload is the stored word throughout, as it is after `push(storage)`. */
class FlowExample extends Module:
  val clk = In(Clock())
  val rst = In(Reset(ActiveHigh))

  given defaultDomain: ClockDomain =
    ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  /** Every port of this module, under one name. */
  val io = Master(FlowExampleIO())

  val storage = Reg(UInt(8), init = 0.U(8))

  // R11 — the answer's valid is combinational and must have a default. The
  // machine raises it in SendEcho; the payload holds the last request's word.
  io.answer.valid := False
  io.answer.payload := storage

  val state = Fsm(FlowState, init = FlowState.Idle):
    in(FlowState.Idle):
      when(io.request.valid):
        storage := io.request.payload
        goto(FlowState.SendEcho)
    in(FlowState.SendEcho):
      io.answer.valid := True
      goto(FlowState.Idle)

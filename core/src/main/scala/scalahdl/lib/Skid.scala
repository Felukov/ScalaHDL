package scalahdl.lib

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, ModuleArg, param}

/** R30 — a register slice: two stages of valid/ready pipeline register, after
  * the classic two-process structure (`ai86_skid.sv`, whose comments are the
  * contract this is ported against).
  *
  * A slice decouples a producer from a consumer by exactly one beat of slack
  * and a `ready` that flows back when the slack is spent. It is the first
  * component in `scalahdl.lib` — modules a design *imports*, as distinct from
  * `examples/`, which demonstrate — and it is what a same-domain `queue(2)`
  * (R8) is. A cross-domain queue is R5's `AsyncFifo`, unchanged.
  *
  * ```scala
  * val slice = inst(new Skid(width = 8))
  * slice.enable := en_fall          // or True, in a design with no enable
  * slice.clear  := head_stepped
  * drive.out    <> slice.s          // R8: valid/payload in, ready out
  * slice.m      <> cpu_side.in
  * ```
  *
  * Four guarantees a design may rely on:
  *
  *   - **`m_valid` and `m_payload` are flop outputs.** Every mux — slot versus
  *     input — is on the D side, so the consumer's logic starts at a register.
  *   - **`s_ready` is a flop read** — "the slot is empty" — with **no
  *     combinational path from `m_ready`**. There is no ready chain through
  *     the slice. The slot catches exactly one in-flight beat when the output
  *     stalls, which is what lets a stream run at one beat per enabled cycle
  *     through a registered stage.
  *   - **The slot beat is the older one** and leaves first.
  *   - **`clear`** squashes both valids synchronously; payload is don't-care
  *     once invalid. **`empty`** is both stages idle — nothing in flight past
  *     the producer.
  *
  * **`enable` is the one addition to the reference.** Every register here
  * updates only on an enabled cycle, because a producer whose pulses are one
  * *enable* wide would otherwise be sampled twice: the library has no
  * clock-enable domain (a domain is its clock, R5.5), so the enable is an
  * ordinary input and a design with no enable ties it high. `clear` is inside
  * the enable for the same reason — a one-enable-wide flush pulse acts once.
  *
  * The consequence for the R8 contract is worth stating: a transfer is
  * `valid && ready` **on an enabled cycle**. `s_ready` is not itself gated by
  * `enable`, exactly as the reference does not gate it, so a producer that
  * does not share this slice's enable would see a ready it cannot use. In the
  * design this exists for, producer and slice share one enable.
  *
  * **Two names the reference does not have.** `ai86_skid.sv` makes `m_valid`
  * and `m_data` literal flop Qs; here a port is not a register — R12 gives
  * every signal its own driver block and a port is written by one — so the out
  * stage is `out_valid`/`out_payload` and the ports carry them out through
  * R12.1's continuous assignment. That is the same flop at the same pin: the
  * emitted `assign m_valid = out_valid;` adds a net, not a level of logic. */
final class Skid(
    width: Int,
    resetKind: ResetKind = ResetKind.Async,
    resetPolarity: ResetPolarity = ActiveLow
) extends Module(Skid.args(width, resetKind, resetPolarity)*):

  require(width >= 1, s"Skid width must be at least one bit, got $width")

  // The clocking is written from the ports, and its *shape* is the
  // constructor's. `rst` carries its polarity in its type rather than in its
  // name: a port name is its `val` name (R1), and one module cannot have two.
  val clk = In(Clock())
  val rst = In(Reset(resetPolarity))
  given defaultDomain: ClockDomain = ClockDomain(clk, rst, resetKind, resetPolarity)

  /** Every register below updates only on an enabled cycle. Tie high in a
    * design that has no enable. */
  val enable = In(Bool())

  /** Synchronous squash of both stages. */
  val clear = In(Bool())

  /** R8 — the producer side: `s_valid`, `s_payload` in, `s_ready` out. */
  val s = Slave(Handshake(UInt(width)))

  /** R8 — the consumer side: `m_valid`, `m_payload` out, `m_ready` in. */
  val m = Master(Handshake(UInt(width)))

  /** Both stages idle: no beat in flight past the producer. */
  val empty = Out(Bool())

  // ---- state
  //
  // The slot (`tmp_vld`/`tmp_data` upstream) and the out stage. The payloads
  // have no reset value, as in the reference, and R13 therefore gives them no
  // reset edge either — a payload is don't-care while its valid is low.

  val slot_valid = Reg(Bool(), init = False)
  val slot_payload = Reg(UInt(width))
  val out_valid = Reg(Bool(), init = False)
  val out_payload = Reg(UInt(width))

  // ---- combinational
  //
  // Three reads of flops and one input. `s_ready` reads `slot_valid` and
  // nothing else, which is the no-ready-chain guarantee stated as code.

  /** The out stage loads this cycle: it is idle, or it is being popped. */
  val out_load = Wire(Bool())
  out_load := ~out_valid | m.ready

  s.ready := ~slot_valid
  m.valid := out_valid
  m.payload := out_payload
  empty := ~out_valid & ~slot_valid

  // ---- the two stages
  //
  // R12 gives each register its own driver block, so the reference's single
  // `always_ff` becomes four; the priority *within* each is what has to carry
  // over, and it does, arm for arm. `clear` is written last on the two valids
  // and therefore wins over both, which is where the reference's trailing
  // `if (reset || clear)` went.

  when(enable) {
    // The slot catches the accepted beat when the out stage is occupied and
    // stalled, and drains into it the moment it can load.
    when(s.valid & out_valid & ~m.ready) { slot_valid := True }
      .elsewhen(out_load) { slot_valid := False }
    when(s.valid & ~slot_valid) { slot_payload := s.payload }

    // The out stage. Slot-first on load — the slot beat is the older one.
    when(s.valid | slot_valid) { out_valid := True }
      .elsewhen(m.ready) { out_valid := False }
    when(out_load) {
      when(slot_valid) { out_payload := slot_payload }
        .elsewhen(s.valid) { out_payload := s.payload }
    }

    when(clear) {
      slot_valid := False
      out_valid := False
    }
  }

  // ---- what the slice claims about itself (R27)
  //
  // The reference states this as a concurrent assertion — `tmp_vld |-> m_valid`
  // under `disable iff (reset)` — and it ports across as an immediate one
  // because both signals are flop outputs: the block reads them at the edge,
  // before this cycle's non-blocking updates land, which are the same values a
  // concurrent assertion samples. The reset guard is the domain's and is not
  // written here (R13).
  //
  // The claim is the structure of the design read back as a sentence. The slot
  // is written only under `s.valid & out_valid & ~m.ready`, whose second term
  // is the out stage being occupied; so a full slot with an empty out stage
  // would mean the two stages had got out of order, and the older beat — the
  // one in the slot, which leaves first — would be behind the newer one.
  simOnly {
    check(~slot_valid | out_valid,
      "the slot is occupied while the out stage is empty")
  }

object Skid:

  /** R16 — `width` always names the module; the reset *shape* names it only
    * when it is not the library default, so `Skid(8)` is `Skid_w8` and
    * `Skid(8, ResetKind.Sync, ActiveHigh)` is `Skid_w8_rst_sync_high`. Both
    * values take part in identity either way: two slices resetting differently
    * are two modules, named apart or not. */
  private def args(width: Int, kind: ResetKind, polarity: ResetPolarity): Seq[ModuleArg] =
    Seq(param("w", width),
        ResetShape.arg(kind, polarity,
          "Skid resets its two valids, so ResetKind.None is not one of its shapes"))

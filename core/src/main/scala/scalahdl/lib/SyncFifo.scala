package scalahdl.lib

import scalahdl.clock.ResetKind
import scalahdl.core.{MemoryKind, ResetPolarity}
import scalahdl.core.ResetPolarity.ActiveLow
import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, ModuleArg, namedParam, param}

/** R32 — a first-in, first-out queue of `depth` words in one clock domain:
  * R8's same-domain `queue(depth)` beyond the two of R30's `Skid`, and the
  * pair of R5's cross-domain `AsyncFifo`.
  *
  * {{{
  *          ready = not full                            valid = not empty
  *   s ─ valid, payload ──► words[tail]  …  words[head] ──► payload ─ m
  *     ◄── ready                                               ready ──►
  *
  *   tail, head: positions one bit wider than an address, so full and empty differ
  * }}}
  *
  * ```scala
  * val keys = inst(new SyncFifo(depth = 8, width = 10, resetKind = ResetKind.Sync,
  *                              resetPolarity = ActiveHigh))
  * producer.out <> keys.s
  * keys.m       <> consumer.in
  * ```
  *
  *   - **`s.ready` is not full**, read from the two positions only: no path
  *     from `m.ready`, and a full queue refuses a word even on the clock one
  *     leaves.
  *   - **`m.valid` is not empty**, from the positions only, and `m.payload`
  *     is the word at `head`: a word taken on one clock is offered from the
  *     next, and nothing passes from `s` to `m` in one.
  *   - **Order is kept**, and no word is lost or offered twice.
  *   - **The depth is a power of two**, so a position wraps by overflowing.
  *   - **The words are in `Registers` or `LutRam`**, both read on the clock
  *     they are addressed. A `BlockRam`'s read is a clock late; a queue on one
  *     is another structure, and is refused.
  */
final class SyncFifo(
    depth: Int,
    width: Int,
    storage: MemoryKind = MemoryKind.Registers,
    resetKind: ResetKind = ResetKind.Async,
    resetPolarity: ResetPolarity = ActiveLow
) extends Module(SyncFifo.args(depth, width, storage, resetKind, resetPolarity)*):

  require(depth >= 2 && (depth & (depth - 1)) == 0,
    s"SyncFifo depth must be a power of two, 2 or more, got $depth")
  require(width >= 1, s"SyncFifo width must be at least one bit, got $width")
  require(storage != MemoryKind.BlockRam,
    "SyncFifo holds its words in Registers or LutRam: a BlockRam is read a clock late, " +
    "and a queue on one is another structure")

  val clk = In(Clock())
  val rst = In(Reset(resetPolarity))
  given defaultDomain: ClockDomain = ClockDomain(clk, rst, resetKind, resetPolarity)

  /** R8 — the producer's side: `s_valid`, `s_payload` in, `s_ready` out. */
  val s = Slave(Handshake(UInt(width)))

  /** R8 — the consumer's side: `m_valid`, `m_payload` out, `m_ready` in. */
  val m = Master(Handshake(UInt(width)))

  private val addressWidth = Integer.numberOfTrailingZeros(depth)
  private val positionWidth = addressWidth + 1

  /** The words held. */
  val words = storage match
    case MemoryKind.Registers => Registers(depth, UInt(width))
    case _ => LutRam(depth, UInt(width))

  /** Where the oldest word is. */
  val head = Reg(UInt(positionWidth), init = 0)
  /** Where the next word goes. */
  val tail = Reg(UInt(positionWidth), init = 0)

  /** No place holds a word: the two positions are equal. */
  val empty = Wire(Bool())
  empty := head === tail
  /** Every place holds a word: the same address, a lap apart. */
  val full = Wire(Bool())
  full := (head(addressWidth) =/= tail(addressWidth)) &
          (head(addressWidth - 1, 0) === tail(addressWidth - 1, 0))

  s.ready := ~full
  m.valid := ~empty

  /** The oldest word, read at `head`. */
  val oldest = words.readAsync(head(addressWidth - 1, 0), ReadFirst)
  m.payload := oldest

  when(s.valid & ~full) {
    words.write(tail(addressWidth - 1, 0), s.payload)
    tail := tail + 1
  }
  when(m.valid & m.ready) {
    head := head + 1
  }

object SyncFifo:

  /** R16 — depth and width always name the module (`SyncFifo_d8_w10`); the
    * storage names it when it is not `Registers`, and the reset shape when it
    * is not the library default. */
  private def args(depth: Int, width: Int, storage: MemoryKind,
                   kind: ResetKind, polarity: ResetPolarity): Seq[ModuleArg] =
    // A string takes part in identity and not in the name.
    val storageArg = storage match
      case MemoryKind.Registers => param("storage", "registers")
      case other => namedParam("storage", storage, other.toString.toLowerCase.nn)
    Seq(param("d", depth), param("w", width), storageArg,
        ResetShape.arg(kind, polarity,
          "SyncFifo resets its two positions, so ResetKind.None is not one of its shapes"))

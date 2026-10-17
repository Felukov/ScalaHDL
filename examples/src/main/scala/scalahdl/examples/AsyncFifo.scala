package scalahdl.examples

import scalahdl.core.Expr
import scalahdl.dsl.{*, given}
import scalahdl.elab.{param, Module}

/** Bundle payload used by the Appendix A async-FIFO validation exercise. */
final case class FifoPayload(width: Int) extends Bundle:
  val data = UInt(width)

/** A lossless dual-clock FIFO with ordinary valid/ready endpoints.
  *
  * Principle of operation:
  *
  *   1. Domain A owns the write pointer and writes accepted payloads into the
  *      memory. Domain B owns the read pointer and reads payloads from it.
  *   2. Each pointer has `addressWidth + 1` bits. The low bits select a memory
  *      slot; the extra high bit records which lap of the circular buffer the
  *      pointer is on, distinguishing equal addresses that mean empty from
  *      equal addresses that mean full.
  *   3. Binary pointers are convenient for incrementing and addressing, but a
  *      binary increment may toggle several bits at once. Therefore only their
  *      Gray-code representations cross clock domains: adjacent Gray values
  *      differ by one bit and can safely pass through `SyncChain`.
  *   4. Each domain decodes the synchronized foreign Gray pointer locally and
  *      compares it with its own pointer. The writer deasserts `ready` before
  *      it would overtake unread data; the reader deasserts `valid` while both
  *      pointers are equal.
  *   5. Payload bits never pass through synchronizer registers. The
  *      dual-domain memory is the data crossing; synchronized pointers ensure
  *      the reader only addresses slots whose writes are already visible.
  *
  * Synchronizer latency makes status conservative rather than unsafe: after a
  * read, `write.ready` may remain low for a few A clocks, and after a write,
  * `read.valid` may remain low for a few B clocks. This reduces instantaneous
  * throughput around empty/full boundaries but cannot lose or duplicate data.
  *
  * The payload blueprint is identity-only under R16.1, while `depth` contributes
  * to the emitted name. Consequently the default artifact is `AsyncFifo_d16`. */
final class AsyncFifo(
    depth: Int,
    payload: FifoPayload
) extends Module(param("d", depth), param("payload", payload)) with DualClock:
  def this() = this(16, FifoPayload(8))

  require(depth >= 2 && (depth & (depth - 1)) == 0,
    s"AsyncFifo depth must be a power of two >= 2, got $depth")

  // A power-of-two depth lets the low pointer bits wrap naturally. The extra
  // MSB toggles on each lap and is used only for full/empty discrimination.
  private val addressWidth = Integer.numberOfTrailingZeros(depth)
  private val pointerWidth = addressWidth + 1
  private val zeroPointer = Expr.TypeCast(GrayCode(pointerWidth), 0.U(pointerWidth))

  // Select A as the ordinary default; withClock supplies B's local context.
  given defaultDomain: ClockDomain = clockA

  // `write` is consumed in domain A; `read` is produced in domain B. A transfer
  // occurs on a local rising edge exactly when both valid and ready are high.
  val write = Slave(Handshake(FifoPayload(payload.width)))
  val read = Master(Handshake(FifoPayload(payload.width)))

  // One physical array has an A-domain write port and a B-domain asynchronous
  // read port. This memory, not the pointer synchronizers, carries payload data.
  val memory = LutRam(depth, payload)

  // Keep binary and Gray forms together in each domain. They advance as a pair
  // only on a successful transfer. Both pairs are declared here so each domain
  // can subsequently synchronize the foreign Gray register.
  val wbin = Reg(UInt(pointerWidth), init = 0.U(pointerWidth))
  val wgray = Reg(GrayCode(pointerWidth), init = zeroPointer)
  val rbin = Reg(UInt(pointerWidth), init = 0.U(pointerWidth))(
    using module, clockB, summon[RegElab[scalahdl.core.Type]])
  val rgray = Reg(GrayCode(pointerWidth), init = zeroPointer)(
    using module, clockB, summon[RegElab[scalahdl.core.Type]])

  // -------------------------------------------------------------------------
  // Write side (clock A)
  // -------------------------------------------------------------------------
  withClock(clockA):
    // Observe B's read progress only through a two-flop Gray synchronizer, then
    // decode it back to binary for local full detection.
    val rgraySync = SyncChain(rgray)
    val rbinSync = Wire(UInt(pointerWidth))
    rbinSync := rgraySync.fromGray

    // Compute the pointer value that would follow one accepted write. The
    // context gives literal `1` the pointer width; no width annotation is needed.
    val wbinNext = Wire(UInt(pointerWidth))
    wbinNext := wbin + 1.U
    val wgrayNext = Wire(GrayCode(pointerWidth))
    wgrayNext := wbinNext.asGray

    // Full detection compares the *next* write location against the observed
    // read location. Equal low bits plus a different lap bit means advancing
    // would catch the reader, so this conservative implementation leaves that
    // slot unused until synchronized read progress arrives.
    val wnextWrap = Wire(Bool())
    wnextWrap := wbinNext(addressWidth) =/= rbinSync(addressWidth)
    val wnextAddress = Wire(UInt(addressWidth))
    wnextAddress := wbinNext(addressWidth - 1, 0)
    val syncedReadAddress = Wire(UInt(addressWidth))
    syncedReadAddress := rbinSync(addressWidth - 1, 0)
    val wnextSameAddress = Wire(Bool())
    wnextSameAddress := wnextAddress === syncedReadAddress
    val full = Wire(Bool())
    full := wnextWrap & wnextSameAddress

    // Back-pressure is entirely local to A. `writeFire` is the sole write
    // enable, keeping both memory contents and pointers unchanged otherwise.
    val writeFire = Wire(Bool())
    write.ready := ~full
    writeFire := write.valid & write.ready

    // The lap bit is not part of the physical memory address.
    val writeAddress = Wire(UInt(addressWidth))
    writeAddress := wbin(addressWidth - 1, 0)
    memory.write(writeAddress.ref, write.payload, writeFire.ref)

    when(writeFire):
      wbin := wbinNext
    when(writeFire):
      wgray := wgrayNext

  // -------------------------------------------------------------------------
  // Read side (clock B): the write side's mirror image
  // -------------------------------------------------------------------------
  withClock(clockB):
    // Synchronizer latency means newly written data is reported later, never
    // earlier: `read.valid` stays conservatively low until B observes the write.
    val wgraySync = SyncChain(wgray)
    val wbinSync = Wire(UInt(pointerWidth))
    wbinSync := wgraySync.fromGray

    // Matching complete pointers (address and lap) means there is no unread
    // entry. Unlike full detection, empty needs no next-pointer look-ahead.
    val empty = Wire(Bool())
    empty := rbin === wbinSync
    read.valid := ~empty

    // The consumer controls ready; the read pointer advances only when an item
    // is both available and accepted.
    val readFire = Wire(Bool())
    readFire := read.valid & read.ready
    // Present the current head continuously. Stability while stalled follows
    // because rbin does not change until `readFire`.
    val readAddress = Wire(UInt(addressWidth))
    readAddress := rbin(addressWidth - 1, 0)
    val readWord = memory.readAsync(readAddress, ReadFirst)
    read.payload.data := readWord.field("data")

    val rbinNext = Wire(UInt(pointerWidth))
    rbinNext := rbin + 1.U
    val rgrayNext = Wire(GrayCode(pointerWidth))
    rgrayNext := rbinNext.asGray
    when(readFire):
      rbin := rbinNext
    when(readFire):
      rgray := rgrayNext

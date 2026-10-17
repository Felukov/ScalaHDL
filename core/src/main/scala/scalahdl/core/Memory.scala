package scalahdl.core

import scalahdl.clock.ClockDomain

/** R15 — Explicit physical memory family. The choice is behavioural: block RAM
  * only permits synchronous reads, while registers and LUT RAM permit
  * asynchronous reads. */
enum MemoryKind:
  case Registers, LutRam, BlockRam

/** R15.3 — Deliberate, per-read-port read-during-write behaviour.
  *
  * `WriteFirstPort` is the true-dual-port case and differs from `WriteFirst`
  * in *which* writes it bypasses: its own port's, rather than every write on
  * the clock. A memory has reads and writes and no object pairing them, so
  * "its own port" is the writes at **this read's address** — which is what one
  * physical port of a dual-port RAM is: one address with a read and a write on
  * it. */
enum ReadDuringWrite:
  case ReadFirst, WriteFirst, WriteFirstPort, Undefined

/** R15.4 — Deterministic initial contents for one memory.
  *
  * The image is *named*, never embedded: emission places the load in an
  * `initial` block and the simulator reads the file at time zero, resolving the
  * path against its own working directory. Keeping the image out of the Verilog
  * is what makes changing firmware a matter of rewriting one text file rather
  * than re-elaborating the design, and it keeps emission size independent of
  * memory depth. */
enum MemoryInit:
  /** `$readmemh` — one whitespace-separated hexadecimal word per entry,
    * ascending address from index zero. Entries the file does not reach keep
    * the simulator's uninitialised value. */
  case HexFile(path: String)

/** One read port. `result` names the scalar packed result signal. A synchronous
  * result is registered by the memory inference block and is valid one cycle
  * after `address`; an asynchronous result is a continuous array read.
  *
  * R15.6 — `enable = None` reads on every active edge; `Some(expr)` updates the
  * result register only when the one-bit expression is true, which is the read
  * side of the same template R15.5 keeps for writes: `if (enable) q <= mem[a]`.
  * An asynchronous read has no register to gate and therefore never carries
  * one. */
final case class MemoryReadPort(
  result: SignalId,
  address: Expr,
  synchronous: Boolean,
  policy: ReadDuringWrite,
  domain: ClockDomain,
  loc: Loc,
  enable: Option[Expr] = None
)

/** One synchronous write port. `enable = None` is unconditional; `Some(expr)`
  * writes only when the one-bit expression is true. Keeping the enable on the
  * memory port (rather than lowering it to an unrelated statement) preserves
  * the canonical inference shape for block/LUT/register memories. */
final case class MemoryWritePort(
  address: Expr,
  data: Expr,
  enable: Option[Expr],
  domain: ClockDomain,
  loc: Loc
)

/** R17/R15 — A memory remains a first-class immutable IR node. Element values
  * are stored packed, including Bundle and Vec elements, so the portable
  * Verilog declaration always has the canonical `reg [W-1:0] mem [0:N-1]`
  * shape. */
final case class Memory(
  name: String,
  kind: MemoryKind,
  depth: Int,
  elemType: Type,
  init: Option[MemoryInit],
  reads: List[MemoryReadPort],
  writes: List[MemoryWritePort],
  loc: Loc
)

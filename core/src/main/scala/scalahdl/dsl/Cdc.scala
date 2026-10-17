package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module
import scalahdl.clock.ClockDomain

/** R05 — Explicit CDC primitives. Each primitive is evaluated in the active
  * destination domain and is the only public API that marks a foreign read as
  * blessed. */
def SyncChain(signal: DslSignal, stages: Int = 2)(using m: Module, cd: ClockDomain): DslSignal =
  require(stages >= 2, s"SyncChain requires at least two stages, got $stages")
  signal.typeOf match
    case Type.Bool | Type.GrayCode(_) => ()
    case Type.Bits(1)                 => ()
    case other =>
      throw CdcElaborationError(
        s"SyncChain accepts Bool, Bits(1), or GrayCode; got $other. Use AsyncFifo for multi-bit data")

  val destination = m.builder.activeDomain(cd)
  var previous = signal
  (0 until stages).foreach { index =>
    val stage = m.builder.addSyntheticReg("sync", signal.typeOf, destination)
    val source =
      if index == 0 then Expr.Cdc(CdcPrimitive.SyncChain(stages), previous.ref, Loc.NoLoc)
      else previous.ref
    m.builder.addAssign(stage, source, Loc.NoLoc)
    previous = stage
  }
  previous

def PulseSync(signal: DslSignal)(using m: Module, cd: ClockDomain): DslSignal =
  require(signal.typeOf == Type.Bool || signal.typeOf == Type.Bits(1),
    s"PulseSync accepts a single-bit event, got ${signal.typeOf}")
  val destination = m.builder.activeDomain(cd)
  val result = m.builder.addSyntheticReg("pulseSync", Type.Bool, destination)
  m.builder.addAssign(result, Expr.Cdc(CdcPrimitive.PulseSync, signal.ref, Loc.NoLoc), Loc.NoLoc)
  result

/** The destination-scoped abstract data crossing (R05). A FIFO built from it
  * is `scalahdl.lib.SyncFifo`; this marker establishes the CDC safety contract
  * and a destination-domain result. */
def AsyncFifo(signal: DslSignal)(using m: Module, cd: ClockDomain): DslSignal =
  val destination = m.builder.activeDomain(cd)
  val result = m.builder.addSyntheticReg("asyncFifo", signal.typeOf, destination)
  m.builder.addAssign(result, Expr.Cdc(CdcPrimitive.AsyncFifo, signal.ref, Loc.NoLoc), Loc.NoLoc)
  result

/** Explicitly bypass CDC safety for one expression. */
def unsafeCrossDomain(signal: DslSignal, justification: String): Expr =
  val reason = justification.trim
  if reason.isEmpty then
    throw CdcElaborationError("unsafeCrossDomain requires a non-empty written justification")
  Expr.Cdc(CdcPrimitive.Unsafe(reason), signal.ref, Loc.NoLoc)

/** The clocks of one source.
  *
  * A statement, made once, in the module that has the clocks: they are taps of
  * one PLL, or one is a division of another, so every edge of one is timed
  * against the other and a value either side presents is settled before the
  * other side's edge samples it. For the crossing check the family is one clock — a read
  * between two of its members is not a crossing and carries no marker — the
  * report lists the family, and a family naming two of this module's input
  * clocks is a contract its parent is held to when the parent freezes.
  *
  * No ratio and no phase: the check uses neither, and the number lives with
  * the PLL that makes it. The operands are this module's own `Clock` signals —
  * its ports, its `Wire(Clock())`s, the net a child's output clock lands on —
  * and never a child's input, which is a clock this module hands *down*. */
def relatedClocks(first: DslSignal, second: DslSignal, more: DslSignal*)(using m: Module): Unit =
  val clocks = first :: second :: more.toList
  clocks.foreach { clock =>
    if clock.typeOf != Type.Clock then
      throw CdcElaborationError(
        s"relatedClocks takes clocks, and `${clock.name}` is ${clock.typeOf}")
    val own = clock.owner.contains(m.builder) || clock.instanceOutput.isDefined
    if !own || !m.builder.declaresClock(clock.id) then
      throw CdcElaborationError(
        s"`${clock.name}` is not a clock of this module; a family is stated where the " +
        "clocks are, on the module's own ports and wires")
  }
  if clocks.map(_.id).distinct.sizeIs < clocks.size then
    throw CdcElaborationError("relatedClocks names one clock twice")
  m.builder.addRelatedClocks(clocks.map(_.id))

/** Stable elaboration error for malformed CDC primitive use. */
final case class CdcElaborationError(detail: String)
    extends RuntimeException(s"CDC_UNSAFE_CROSSING: $detail")

/** Declare the distinct gray-coded hardware type. */
def GrayCode(width: Int): Type =
  require(width > 0, s"GrayCode width must be positive, got $width")
  Type.GrayCode(width)

/** Convert a named binary UInt/Bits signal to GrayCode. */
extension (signal: DslSignal)
  def asGray: Expr = signal.typeOf match
    case Type.UInt(width) => Expr.TypeCast(Type.GrayCode(width), binaryToGray(signal.ref, width))
    case Type.Bits(width) => Expr.TypeCast(Type.GrayCode(width), binaryToGray(signal.ref, width))
    case Type.GrayCode(_) => signal.ref
    case other => throw CdcElaborationError(s"asGray requires UInt or Bits, got $other")

  /** Decode a GrayCode signal to UInt through a width-bounded XOR fold.
    *
    * The fold is a bit-pattern operation, so the gray value is
    * reinterpreted as `UInt` at the operand before any operator sees it. That
    * keeps the lowered IR admissible under the type table (`GrayCode` admits
    * only its preserving operations, of which this is one) rather than
    * relying on the validator not looking. */
  def fromGray: Expr = signal.typeOf match
    case Type.GrayCode(width) =>
      val bits = Expr.TypeCast(Type.UInt(width), signal.ref)
      (1 until width).foldLeft[Expr](bits) { (decoded, shift) =>
        Expr.BinOp("^", decoded, Expr.BinOp(">>", bits,
          Expr.uint(BigInt(shift), width)))
      }
    case other => throw CdcElaborationError(s"fromGray requires GrayCode, got $other")

  /** Increment in binary and convert the next value back to gray. */
  def grayIncrement: Expr = signal.typeOf match
    case Type.GrayCode(width) =>
      val binary = signal.fromGray
      val next = Expr.BinOp("+", binary, Expr.uint(BigInt(1), width))
      Expr.TypeCast(Type.GrayCode(width), Expr.BinOp("^", next, Expr.BinOp(">>", next,
        Expr.uint(BigInt(1), width))))
    case other => throw CdcElaborationError(s"grayIncrement requires GrayCode, got $other")

  def grayEquals(other: DslSignal): Expr = (signal.typeOf, other.typeOf) match
    case (Type.GrayCode(left), Type.GrayCode(right)) if left == right =>
      Expr.BinOp("==", signal.fromGray, other.fromGray)
    case _ => throw CdcElaborationError(
      s"grayEquals requires equal-width GrayCode operands, got ${signal.typeOf} and ${other.typeOf}")

private def binaryToGray(expr: Expr, width: Int): Expr =
  Expr.BinOp("^", expr, Expr.BinOp(">>", expr,
    Expr.uint(BigInt(1), width)))

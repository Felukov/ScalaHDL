package scalahdl.dsl

import scala.collection.mutable
import scalahdl.core.*
import scalahdl.diag.{Code, Diagnostic, RelatedLoc, Severity, ValidationFailed}

/** R24 — Named encodings.
  *
  * ```scala
  * object AluOp extends Encoding(4):
  *   val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And = Value
  * ```
  *
  * A member carries its own width, so it needs no annotation in any position a
  * literal would need one — `is(AluOp.Or)`, `op := AluOp.Add`, `Mux(alt,
  * AluOp.Sub, AluOp.Add)`. That is the whole point: R6.1 can give a case label
  * the selector's width, but it stops at an assignment boundary by design, so
  * without R24 the decoder driving the selector keeps writing the width down.
  *
  * This is a surface construct over `UInt`. `AluOp()` *is* `UInt(4)` and
  * `AluOp.Or` *is* the four-bit literal `8`; the IR, the validation phases and
  * the emitter are unchanged, and the only trace an encoding leaves is the
  * `EncodingInfo` recorded on the signals declared from it, which exists so a
  * diagnostic can say `8 (AluOp.Or)`.
  *
  * `Value` is `inline` for the same reason `Wire` and `In` are: `Source.capture`
  * expands at the member's own `val`, so `val Add, Sub = Value` — two `val`s
  * after desugaring — names both, exactly as R10's `val a, b = In(UInt(8))`
  * declares two ports. */
abstract class Encoding(pinnedWidth: Int = 0):

  private val _members = mutable.ListBuffer.empty[EncodingValue]
  private var nextCode = 0
  private var frozenWidth: Option[Int] = None

  /** The encoding's own name, taken from the declaring object. Used only in
    * diagnostics, so an anonymous declaration falls back rather than failing. */
  val name: String =
    val fromClass =
      try getClass.getSimpleName.stripSuffix("$")
      catch case _: Throwable => ""
    if fromClass.nonEmpty && !fromClass.forall(_.isDigit) then fromClass else "Encoding"

  /** The members, in declaration order. */
  def values: List[EncodingValue] = _members.toList

  /** The width every member is emitted at. Pinned by `Encoding(w)`; otherwise
    * the minimum that holds the largest code, and never less than one bit.
    *
    * Reading it freezes it. Referring to any member forces the declaring
    * object's body to completion first, so an inferred width cannot change
    * afterwards — `ENCODING_SEALED` is what keeps that true for a declaration
    * that is not a plain object body. */
  def width: Int = frozenWidth.getOrElse:
    val inferred =
      if pinnedWidth > 0 then pinnedWidth
      else math.max(1, _members.map(m => BigInt(m.code).bitLength).maxOption.getOrElse(1))
    frozenWidth = Some(inferred)
    inferred

  /** `AluOp()` — the encoding read as a type: `In(AluOp())`, `Wire(AluOp())`,
    * `Reg(AluOp(), init = AluOp.Add)`. `In(AluOp)` is the same declaration. */
  def apply(): Encoding = this

  /** The hardware type an encoding stands for (R24: `UInt`, not a new type). */
  def toType: Type = Type.UInt(width)

  /** The metadata recorded on a signal declared from this encoding. */
  private[scalahdl] def info: EncodingInfo =
    val named = _members.toList.flatMap: m =>
      m.captured.map(memberName => BigInt(m.code) -> memberName)
    EncodingInfo(name, width, named)

  /** `val Add, Sub = Value` — the next code, counting from zero. */
  inline protected def Value: EncodingValue = member(Source.capture, None)

  /** `val Add = Value(4)` — a pinned code; counting resumes at `code + 1`. For
    * an encoding fixed by an external document rather than by its own order. */
  inline protected def Value(code: Int): EncodingValue = member(Source.capture, Some(code))

  protected def member(src: Source, pinned: Option[Int]): EncodingValue =
    frozenWidth.foreach: w =>
      throw ValidationFailed.one(Diagnostic(
        severity = Severity.Error,
        code = Code.ENCODING_SEALED,
        summary = s"encoding `$name` gained a member after its width ($w) was read",
        primaryLoc = src.loc,
        fix = Some("declare every member in the encoding's own body, before it is used")))

    val code = pinned.getOrElse(nextCode)
    if code < 0 || (pinnedWidth > 0 && BigInt(code).bitLength > pinnedWidth) then
      throw ValidationFailed.one(Diagnostic(
        severity = Severity.Error,
        code = Code.ENCODING_CODE_OUT_OF_RANGE,
        summary =
          if code < 0 then s"encoding `$name` member code `$code` is negative (a code is a bit pattern)"
          else s"encoding `$name` member code `$code` needs ${BigInt(code).bitLength} bits, but the encoding is $pinnedWidth wide",
        primaryLoc = src.loc,
        fix = Some(s"widen the encoding to `Encoding(${BigInt(code).bitLength})` or pin a code that fits")))

    _members.find(_.code == code).foreach: previous =>
      throw ValidationFailed.one(Diagnostic(
        severity = Severity.Error,
        code = Code.ENCODING_DUPLICATE_CODE,
        summary = s"encoding `$name` gives code `$code` to two members",
        primaryLoc = src.loc,
        related = List(RelatedLoc(previous.loc, s"`${previous.name}` already has code `$code`")),
        fix = Some("pin distinct codes with `Value(n)`, or let them count from zero")))

    nextCode = code + 1
    val value = new EncodingValue(this, code, src.name, src.loc)
    _members += value
    value

/** One member of an encoding: a constant that knows its own width, its code,
  * and the name the user gave it.
  *
  * `expr` is what every operand position sees, through the conversion below. It
  * is an ordinary literal — an encoding member and a hand-written `8.U(4)`
  * produce the same IR node and the same Verilog. */
final class EncodingValue private[dsl] (
    val encoding: Encoding,
    val code: Int,
    private[dsl] val captured: Option[String],
    val loc: Loc):

  /** The member's name (R1 capture), or its code where it was not bound to a
    * `val` and therefore has no name to report. */
  def name: String = captured.getOrElse(code.toString)

  /** The constant, at the encoding's width. */
  def expr: Expr = Expr.uint(BigInt(code), encoding.width)

  override def toString: String = s"${encoding.name}.$name"

/** R24 — a member reaches every operand position as its constant. The same
  * shape as `DslSignal => Expr`: the operator surface is defined once on `Expr`
  * and everything else converts into it. */
given Conversion[EncodingValue, Expr] = _.expr

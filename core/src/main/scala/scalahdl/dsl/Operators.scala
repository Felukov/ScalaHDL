package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module

/** R21 — The complete expression surface.
  *
  * Every operator is defined once, on `Expr`. A `DslSignal` reaches it through
  * the single `DslSignal → Expr` conversion (see `DslSignal.scala`), which
  * Scala 3 applies in argument position *and* to an extension method's
  * receiver. So operand-kind completeness — a named signal, an anonymous
  * expression, or a literal, on either side, in every combination — is a
  * property of the definition rather than of how many overloads someone
  * remembered to write. `(a - b) & mask` and `mask & (a - b)` are the same
  * three calls in a different order.
  *
  * Applying that conversion to a receiver is a Scala *feature* use, so a
  * project that writes `a + b` compiles with `-language:implicitConversions`,
  * exactly as `core` and the examples do. It is the one deliberate implicit in
  * the library, and the flag is how a consumer acknowledges it.
  *
  * Every operator builds an anonymous tree and declares nothing (R2): the
  * emitted wire count still equals the named-`val` count.
  *
  * | Operator | Operands | Result |
  * |---|---|---|
  * | `+` `-`            | `UInt`, `SInt`, same width      | operand kind, width `w` |
  * | `*`                | `UInt`, `SInt`, widths may differ | operand kind, width `w1 + w2` (R21.3) |
  * | `&` `\|` `^` `~`   | `UInt`, `SInt`, `Bits`, `Bool`  | operand type |
  * | `!`                | `Bool`                          | `Bool` |
  * | `<<` `>>`          | `UInt`, `SInt`, `Bits`          | left operand's type and width (R21.1) |
  * | `>>>`              | `SInt`                          | `SInt`, left operand's width (R21.1) |
  * | `===` `=/=`        | any bit-typed, same type/width  | `Bool` |
  * | `<` `<=` `>` `>=`  | `UInt`, `SInt`, same width      | `Bool` (R21.2) |
  * | `orR` `andR` `xorR`| any bit-typed                   | `Bool` |
  * | `.resize(w)`       | `UInt`, `SInt`, `Bits`          | operand kind, width `w` (R21.4) |
  * | `.asUInt` `.asSInt` `.asBits` | any bit-typed        | requested kind, width unchanged (R21.4) |
  *
  * Type admission is enforced in `WidthPhase`, not here: rejecting `a < b` on
  * `Bits` at the Scala type level would need one Scala type per hardware type,
  * and R6 deliberately keeps widths and kinds as *values*. The diagnostic names
  * both operands, their types, and the cast that fixes it (R19). */

extension (e: Expr)

  // ---- arithmetic (R21, R21.3) ---------------------------------------

  def + (o: Expr): Expr = Expr.BinOp("+", e, o)
  def - (o: Expr): Expr = Expr.BinOp("-", e, o)

  /** R21.3 — the full `w1 + w2` product. Lossless, so this is the one
    * arithmetic operator whose operands may differ in width without a
    * `resize`; storing the result in an operand-width signal is an explicit
    * `.resize(w)`, and the error when it is omitted names both widths. */
  def * (o: Expr): Expr = Expr.BinOp("*", e, o)

  // ---- bitwise and logical (R21) --------------------------------------

  def & (o: Expr): Expr = Expr.BinOp("&", e, o)
  def | (o: Expr): Expr = Expr.BinOp("|", e, o)
  def ^ (o: Expr): Expr = Expr.BinOp("^", e, o)

  def unary_~ : Expr = Expr.UnOp("~", e)
  def unary_! : Expr = Expr.UnOp("!", e)

  /** `orR` — 1 when any bit is set. Emits Verilog's `(|a)`. */
  def orR: Expr = Expr.UnOp("|", e)
  /** `andR` — 1 when every bit is set. Emits `(&a)`. */
  def andR: Expr = Expr.UnOp("&", e)
  /** `xorR` — the parity of the operand. Emits `(^a)`. */
  def xorR: Expr = Expr.UnOp("^", e)

  // ---- shifts (R21.1) --------------------------------------------------

  /** `<<` — logical shift left on every admitted type. The result takes the
    * left operand's type and width; bits shifted past the width are dropped,
    * as `+` drops its carry (R6.2). The amount is unsigned and of any width. */
  def << (n: Expr): Expr = Expr.BinOp("<<", e, n)

  /** `>>` — **logical** shift right on every admitted type: zero fill, always,
    * including on `SInt`, where it shifts the two's-complement pattern. */
  def >> (n: Expr): Expr = Expr.BinOp(">>", e, n)

  /** `>>>` — **arithmetic** shift right, legal on `SInt` only; `UInt`/`Bits`
    * is an elaboration error naming `.asSInt`. The distinction between `SRL`
    * and `SRA` stays visible at the call site rather than depending on a
    * declaration elsewhere (R21.1). */
  def >>>(n: Expr): Expr = Expr.BinOp(">>>", e, n)

  // ---- comparisons (R21, R21.2) ---------------------------------------

  def ===(o: Expr): Expr = Expr.BinOp("==", e, o)
  def =/=(o: Expr): Expr = Expr.BinOp("!=", e, o)

  /** R21.2 — ordering is signed by operand type: two `UInt`s compare unsigned,
    * two `SInt`s compare signed, and there is no third case. The `SLT`/`SLTU`
    * pair is written as the same comparison on differently-typed operands. */
  def < (o: Expr): Expr = Expr.BinOp("<", e, o)
  def <=(o: Expr): Expr = Expr.BinOp("<=", e, o)
  def > (o: Expr): Expr = Expr.BinOp(">", e, o)
  def >=(o: Expr): Expr = Expr.BinOp(">=", e, o)

  // ---- width and kind conversions (R21.4) ------------------------------

  /** R21.4 — change width, preserve kind. Widening a `UInt` or `Bits` fills
    * with zeros; widening an `SInt` replicates its sign bit; narrowing keeps
    * the low `w` bits. The policy is read off the operand's type, so no call
    * site chooses it and no call site can choose wrong.
    *
    * Narrowing and sign-extending emit a bit-selection and therefore require a
    * bit-addressable operand (R6.2) — with the exception R6.2 already grants:
    * a narrowing `resize` forming the entire right-hand side of an assignment
    * needs none, because Verilog truncates natively at the target. */
  def resize(w: Int): Expr =
    require(w >= 1, s"resize width must be positive, got $w")
    Expr.Resize(w, e)

  /** R21.4 — reinterpret as unsigned. No bit moves; emits the operand
    * unchanged, which is exactly correct under R21.6's rule that signedness is
    * applied per operation rather than declared. */
  def asUInt(using m: Module): Expr = castTo(e, Type.UInt(_), "asUInt", m)

  /** R21.4 — reinterpret as signed. `-1.S(8).asUInt` is `255` and
    * `x.asUInt.asSInt` is bit-identical to `x`; these are reinterpretations,
    * never conversions. Crossing R6's signedness barrier happens here and
    * only here. */
  def asSInt(using m: Module): Expr = castTo(e, Type.SInt(_), "asSInt", m)

  /** R21.4 — reinterpret as a raw bit vector: the same bits, the same width,
    * read without a signedness interpretation.
    *
    * R07.2 — and it is what asks for an aggregate as bits, because a bundle is
    * a packed vector of them. `pair.asBits` is the whole bundle's word, its
    * first field in the most significant bits. There is no second operation
    * for that: the layout is the declaration, and the packing an emitted
    * concatenation has to spell out is R07.1's flattening, not a conversion
    * the caller performs. */
  def asBits(using m: Module): Expr = castTo(e, Type.Bits(_), "asBits", m)

  /** R13.7 — a reset *derived* rather than received, once and explicitly.
    *
    * The exact inverse of R13.5's `.asserted`, and it exists for the case that
    * rule's own rationale already names — *"a soft reset ORed in"*. R13.5 gets
    * a reset out of `Type.Reset` and into data; without this there is no way
    * back, so a module that computes its own reset can compute it and then not
    * use it:
    *
    * {{{
    * val derived = Wire(Reset(ActiveHigh))
    * derived := (writing & (index === 9) & (data(7, 6) === 3)).asReset(ActiveHigh)
    * private val derivedDomain = ClockDomain(clk, derived, ResetKind.Async, ActiveHigh)
    * }}}
    *
    * **The polarity is stated and is applied, not merely recorded.** The
    * argument says what *asserted* means for the result, so an `ActiveLow`
    * reset is built by inverting — which makes the round trip exact:
    * `x.asReset(p).asserted` is `x` for either polarity. A conversion that only
    * relabelled would make the two spellings disagree, and disagreeing about a
    * reset's polarity is the specific bug R13.5 refuses to allow.
    *
    * **The result needs a name before a domain can take it**, because a
    * sensitivity list is written in terms of one — so this is assigned to a
    * `Wire(Reset(…))` rather than handed to `ClockDomain` directly. The
    * intermediate is therefore the user's `val`, not one the library invented.
    *
    * One bit only. A reset is a level, and a multi-bit reset is not a thing to
    * guess the meaning of. */
  def asReset(polarity: ResetPolarity)(using m: Module): Expr =
    val types = m.builder.typeTable
    ExprType.typeOpt(e, types) match
      case Some(t) if t.bitWidth != 1 =>
        throw CastError(
          s"`.asReset` makes a reset out of one bit and this operand is " +
          s"${t.bitWidth}; a reset is a level, and which bit of a vector is " +
          "the reset is not something to guess")
      case Some(_: Type.Bundle) | Some(_: Type.Vec) =>
        throw CastError(
          "`.asReset` makes a reset out of one bit and does not apply to an " +
          "aggregate")
      case Some(_) => polarity match
        case ResetPolarity.ActiveHigh =>
          Expr.TypeCast(Type.Reset(ResetPolarity.ActiveHigh), e)
        case ResetPolarity.ActiveLow =>
          // Inverted, so that `.asserted` gives back what went in: the
          // argument says what *asserted* means, and this is where it is paid
          // for rather than remembered.
          Expr.TypeCast(Type.Reset(ResetPolarity.ActiveLow), Expr.UnOp("!", e))
      case None =>
        throw CastError(
          "`.asReset` needs the operand's width, and an unwidthed literal has " +
          "none; write the width at the literal")

  /** A **clock** made out of one bit, once and explicitly.
    *
    * {{{
    * val coreClk = Wire(Clock())
    * coreClk := divided.asClock
    * given coreDomain: ClockDomain = ClockDomain(coreClk, ResetKind.Sync, ...)
    * }}}
    *
    * `Clock` is a distinct type so a clock cannot *accidentally*
    * become data or data a clock, and that stays. But a design that *derives*
    * a clock had nowhere to go: `Wire(Clock())` declared fine and nothing could
    * be assigned to it, so a module could compute a divided or gated clock and
    * then find it unusable — the hole the reset conversion closes, one type
    * along, and this is the same door.
    *
    * **There is no polarity to state**, which is the one way this differs from
    * `.asReset`: a clock's active edge is the *domain's*, declared where the
    * domain is, and a conversion that took an edge here would be a second place
    * to say it.
    *
    * One bit only, for the same reason: a clock is a level over time, and which
    * bit of a vector is the clock is not something to guess.
    *
    * *What this does not decide.* Whether a domain built on a derived clock is
    * a *new* domain for the crossing check is the user's statement, made where the
    * domain is declared, exactly as it is for a clock arriving on a port. This
    * conversion makes the signal expressible; it makes no claim about its
    * relationship to the clock it was derived from. */
  def asClock(using m: Module): Expr =
    val types = m.builder.typeTable
    ExprType.typeOpt(e, types) match
      case Some(t) if t.bitWidth != 1 =>
        throw CastError(
          s"`.asClock` makes a clock out of one bit and this operand is " +
          s"${t.bitWidth}; which bit of a vector is the clock is not " +
          "something to guess")
      case Some(_: Type.Bundle) | Some(_: Type.Vec) =>
        throw CastError(
          "`.asClock` makes a clock out of one bit and does not apply to an " +
          "aggregate")
      case Some(_) => Expr.TypeCast(Type.Clock, e)
      case None =>
        throw CastError(
          "`.asClock` needs the operand's width, and an unwidthed literal has " +
          "none; write the width at the literal")

/** A reset read as data, once and explicitly.
  *
  * `rst.asserted` is a `Bool` that is true while the reset is *active*, so an
  * active-low reset comes back already inverted and the polarity is not a fact
  * the reader has to carry. It is the only way out of `Type.Reset`, and there
  * is deliberately no way to ask for the raw *level*: a level is meaningless
  * without the polarity beside it, and asking for one is how a reset ends up
  * driving a child backwards.
  *
  * A reset is a distinct type so it cannot *accidentally* become
  * data, and that is worth keeping. But a hierarchy needs a reset to be data
  * on purpose — a child spelling it the other way round, a soft reset ORed in,
  * a register that must hold while reset is asserted — and refusing that
  * outright does not prevent a bug, it prevents a design. This is the one
  * sanctioned spelling, and the type keeps every unsanctioned one. */
extension (s: DslSignal)
  def asserted(using m: Module): Expr = s.typeOf match
    case Type.Reset(ResetPolarity.ActiveHigh) => Expr.TypeCast(Type.Bool, s.ref)
    case Type.Reset(ResetPolarity.ActiveLow)  =>
      Expr.UnOp("!", Expr.TypeCast(Type.Bool, s.ref))
    case other =>
      throw CastError(
        s"`.asserted` reads a reset's active level and `${s.name}` is $other; " +
        "an ordinary signal is already data")

/** Build a representation-preserving cast. The operand's width comes
  * from the shared typer, which is what lets a cast apply to any
  * anonymous tree — a slice, a sum, a product — and not merely to a named
  * signal. */
private[dsl] def castTo(e: Expr, kind: Int => Type, name: String, m: Module): Expr =
  val types = m.builder.typeTable
  ExprType.typeOpt(e, types) match
    // An aggregate *is* a packed vector of bits, so a cast of one is a
    // cast of that word, at the aggregate's own `bitWidth`. `pair.asBits` is
    // how a bundle is asked for as bits, and `.asUInt` how it is asked for as
    // a number; the concatenation the word is made of is written by
    // `AggregateRead`, which packs every read the same way.
    case Some(t) => Expr.TypeCast(kind(t.bitWidth), e)
    case None =>
      throw CastError(
        s"`.$name` needs the operand's width, and an unwidthed literal has none; " +
        s"write the width at the literal, e.g. `1.U(8).$name`")

/** `Cat(a, b, …)` concatenates into a `UInt` of the summed width, the
  * first argument in the most significant bits. That is the order bundles pack
  * in, so a hand-written concatenation and a read of the bundle
  * itself never disagree.
  *
  * The result is `UInt` regardless of the parts' kinds — a concatenation is a
  * bit pattern, and reading it as signed is an explicit `.asSInt`. Every part
  * must have a known width, since a part whose width was inferred would
  * silently change the width of the whole; an unwidthed literal part is an
  * elaboration error naming the part and the explicit form. */
def Cat(parts: Expr*): Expr =
  require(parts.nonEmpty, "Cat requires at least one part")
  Expr.Cat(parts.toList)

/** `Fill(n, x)` replicates `x` `n` times, yielding a `UInt` of width
  * `n * w`. `n == 0` is an error rather than an empty concatenation: Verilog
  * has no zero-width value, and a fill that vanishes would silently change the
  * width of whatever it was part of. */
def Fill(n: Int, x: Expr): Expr =
  require(n >= 1, s"Fill requires at least one repetition, got $n")
  Expr.Repeat(n, x)

/** Raised when a cast cannot be built (R21.4). A library-surface elaboration
  * error, in the same family as `BitSelectAnonymousError`. */
final case class CastError(detail: String) extends RuntimeException(detail)

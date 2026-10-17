package scalahdl.core

import SignalId.*

/** R21.6 — The shared expression typer.
  *
  * One typing utility, reachable from all three consumers that need it:
  * `Builder`'s R6.1/R21.7 literal resolution, `WidthPhase`'s R6 width and
  * signedness checks, and `VerilogEmitter`'s type-directed `$signed` emission.
  * Before R21 each of those had its own partial answer — `WidthPhase.typeOf`
  * was private to validation, `EmitCtx` could type only signal ids, and the
  * builder's `hasWidth` saw through neither a slice nor a cast. Appendix D
  * findings 4 and 7 are both that split; this closes it.
  *
  * The typer is **total over the IR**: every `Expr` case is matched here (the
  * `enum` makes that checkable) and there is no catch-all returning a plausible
  * wrong type. A node whose type genuinely cannot be computed — an unresolved
  * `Lit` before R6.1 inference has run — yields `None` from `typeOpt` rather
  * than a guess; everything else answers.
  *
  * Two entry points, for the two callers that differ in what an unknown width
  * means:
  *
  *   - `typeOpt` / `widthOpt` — used during elaboration, where an unresolved
  *     literal is the normal state and `None` is the signal to infer.
  *   - `typeOf` / `widthOf` — used after freeze by validation and emission,
  *     where every literal carries a width and a `None` is a defect. These
  *     throw `ExprTypeError` rather than substituting a default.
  *
  * `types` maps a `SignalId` to its declared type; an id absent from it is a
  * malformed IR, not a typing question, so the supplied function may throw. */
object ExprType:

  /** Operator classification. The op strings live in `Expr.BinOp`/`Expr.UnOp`
    * (R17: a new IR node earns its place only when a generic textual operator
    * would lose type meaning), so the *meaning* of each string is defined once,
    * here, and read by the DSL, the validator, and the emitter alike. */
  object Op:
    /** `+` `-` — arithmetic on `UInt`/`SInt` only (R21). */
    def isAdditive(op: String): Boolean = op == "+" || op == "-"
    /** `&` `|` `^` — bitwise, legal on `Bits` and `Bool` too (R21). */
    def isBitwise(op: String): Boolean = op == "&" || op == "|" || op == "^"
    /** `*` — the full product, `w1 + w2` (R21.3). */
    def isMultiply(op: String): Boolean = op == "*"
    /** `<<` `>>` `>>>` — the left operand's type and width (R21.1). */
    def isShift(op: String): Boolean = op == "<<" || op == ">>" || op == ">>>"
    /** `>>>` — arithmetic shift, `SInt` only (R21.1). */
    def isArithmeticShift(op: String): Boolean = op == ">>>"
    /** `<` `<=` `>` `>=` — ordering, signed by operand type (R21.2). */
    def isOrdering(op: String): Boolean =
      op == "<" || op == "<=" || op == ">" || op == ">="
    /** `==` `!=` — equality on any bit-typed operands (R21). */
    def isEquality(op: String): Boolean = op == "==" || op == "!="
    /** Every operator whose result is `Bool` regardless of its operands. */
    def isPredicate(op: String): Boolean = isOrdering(op) || isEquality(op)
    /** `!` — logical negation on `Bool` (R21). */
    def isNot(op: String): Boolean = op == "!"
    /** `~` — bitwise complement, operand type preserved (R21). */
    def isComplement(op: String): Boolean = op == "~"
    /** `orR` `andR` `xorR`, spelled as the Verilog reduction prefixes. A
      * reduction is a `UnOp` and a complement is a `UnOp`, so the strings
      * `&`/`|`/`^` are unambiguous in that position. */
    def isReduction(op: String): Boolean = op == "&" || op == "|" || op == "^"

  /** The type of an expression, or `None` if an unresolved literal makes it
    * unknowable. Used by the builder while inferring literal widths (R6.1,
    * R21.7): `None` means "no context here yet", never "this node has no
    * type". */
  def typeOpt(e: Expr, types: SignalId => Type): Option[Type] = e match
    case Expr.Ref(id) => Some(types(id))

    // R6.1/R6.4 — a literal answers only once *both* its width and its kind are
    // fixed. A bare `Int` starts with neither, and either one still missing
    // means "no context here yet", never "this node has no type".
    case Expr.Lit(_, Some(w), Some(kind)) => Some(LitKind.typeOf(kind, w))
    case Expr.Lit(_, _, _)                => None

    case Expr.UnOp(op, arg) =>
      if Op.isNot(op) then Some(Type.Bool)
      else if Op.isComplement(op) then typeOpt(arg, types)
      else if Op.isReduction(op) then Some(Type.Bool)
      else throw ExprTypeError(s"unknown unary operator `$op`")

    case Expr.BinOp(op, lhs, rhs) =>
      if Op.isPredicate(op) then Some(Type.Bool)
      else if Op.isShift(op) then
        // R21.1 — the result is the left operand's type and width; the amount
        // is a separate operand and contributes nothing to the result.
        typeOpt(lhs, types)
      else if Op.isMultiply(op) then
        // R21.3 — the full product; both widths are needed, so neither
        // operand may be an unresolved literal.
        for
          lt <- typeOpt(lhs, types)
          rt <- typeOpt(rhs, types)
        yield withWidth(lt, lt.bitWidth + rt.bitWidth)
      else if Op.isAdditive(op) || Op.isBitwise(op) then
        // R6 — operands agree in kind and width (checked in `WidthPhase`);
        // either one therefore answers for the result. `max` keeps the type
        // meaningful while a mismatch is still being reported.
        val kind = typeOpt(lhs, types).orElse(typeOpt(rhs, types))
        val lw = typeOpt(lhs, types).map(_.bitWidth)
        val rw = typeOpt(rhs, types).map(_.bitWidth)
        val width = (lw ++ rw).reduceOption(_ max _)
        for
          k <- kind
          w <- width
        yield withWidth(k, w)
      else throw ExprTypeError(s"unknown binary operator `$op`")

    case Expr.Mux(_, whenTrue, whenFalse) =>
      // R21 — the arms agree in type and width (checked in `WidthPhase`), so
      // either arm answers. R21.7: a literal arm takes the other arm's width.
      typeOpt(whenTrue, types).orElse(typeOpt(whenFalse, types))

    case Expr.Slice(_, hi, lo) => Some(Type.UInt(hi - lo + 1))

    // R34 — a runtime-offset window is unsigned of its width, the same type
    // `Slice` has: the window's width is known even though its position is not.
    case Expr.VarSlice(_, _, width) => Some(Type.UInt(width))

    case Expr.Index(base, _) =>
      // R14 — a Vec element keeps its full element type; R6.2 — a scalar
      // bit-select is one bit.
      typeOpt(base, types).map {
        case Type.Vec(_, elem) => elem
        case _: Type.UInt | _: Type.SInt | _: Type.Bits | _: Type.GrayCode => Type.Bool
        case other => other
      }

    case Expr.Field(base, name) =>
      typeOpt(base, types).map {
        case Type.Bundle(fields) =>
          // A missing field is reported by the aggregate phases; typing it as
          // the bundle keeps the width check from inventing a second error.
          fields.find(_._1 == name).map(_._2).getOrElse(Type.Bundle(fields))
        case other => other
      }

    case Expr.Cat(parts) =>
      // R21.5 — the summed width, `UInt` regardless of part kinds. Every part
      // must be width-known; an unwidthed literal part is the R21.7 error.
      val widths = parts.map(p => typeOpt(p, types).map(_.bitWidth))
      if widths.forall(_.isDefined) then Some(Type.UInt(widths.flatten.sum))
      else None

    case Expr.Repeat(n, expr) =>
      // R21.5 — `Fill(n, x)` is `UInt(n * w)`; also the sign-extension form.
      typeOpt(expr, types).map(t => Type.UInt(n * t.bitWidth))

    case Expr.Resize(to, expr) =>
      // R21.4 — width-changing, kind-preserving.
      typeOpt(expr, types).map(t => withWidth(t, to))

    case Expr.TypeCast(to, _) => Some(to)   // R21.4 — representation-preserving

    case Expr.Cdc(_, inner, _) => typeOpt(inner, types)

  /** R14 — the type an assignment *target* names: the root signal's type, or
    * the leaf reached by walking the field/index path.
    *
    * Shared for the same reason `typeOpt` is. `Builder`'s R6.3 resolution asks
    * which leaf a target names in order to give a whole-RHS literal its width,
    * and `WidthPhase`'s assignment check asks the same question to width-check
    * the result. Two answers that could disagree would produce a literal
    * resolved against one width and checked against another — a silent width
    * bug rather than a loud one, which is the one failure mode R6 exists to
    * prevent.
    *
    * The field and index rules are `Expr.Field`'s and `Expr.Index`'s above, so
    * a target and a read of the same path type identically. */
  def lhsType(lhs: Lhs, types: SignalId => Type): Type = lhs match
    case Lhs.Sig(id) => types(id)
    case Lhs.Field(base, name) =>
      lhsType(base, types) match
        case Type.Bundle(fields) =>
          // A missing field is reported by the aggregate phases; typing it as
          // the bundle keeps the width check from inventing a second error.
          fields.find(_._1 == name).map(_._2).getOrElse(Type.Bundle(fields))
        case other => other
    case Lhs.Index(base, _) =>
      lhsType(base, types) match
        case Type.Vec(_, elem) => elem
        case _: Type.UInt | _: Type.SInt | _: Type.Bits | _: Type.GrayCode => Type.Bool
        case other => other
    // R6.7 — a part-select target is the window, unsigned, exactly as
    // `Expr.Slice` types the same window on the reading side. Writing a
    // signed value into a window of an `SInt` is R6.5's kind error and is
    // meant to be: the bits under the window are not a number.
    case Lhs.Slice(_, hi, lo) => Type.UInt(hi - lo + 1)
    case Lhs.VarSlice(_, _, width) => Type.UInt(width)

  /** R6.3 — the width a context offers a literal, or `None` where it offers
    * none and the literal stays R6.1's uninferrable error.
    *
    * Only an **aggregate** offers nothing: it has no scalar width a literal
    * could mean. An aggregate *reads* as its packed bits (R7.2), but this is
    * the writing side, where bits reach a bundle through `unpackBits` alone —
    * so taking the packed width here would invent a meaning the surface does
    * not have.
    *
    * A kind disagreement is *not* handled by withholding the width. Before R6.5
    * it had to be, because `checkAssign` compared widths only and a mis-kinded
    * literal would have slipped through silently; now the assignment reports it
    * by name, so the literal is better off resolved and rejected with a message
    * that names both types than left as an uninferrable-width error. */
  def targetWidth(t: Type): Option[Int] = t match
    case Type.Bundle(_) | Type.Vec(_, _) => None
    case scalar                          => Some(scalar.bitWidth)

  /** The type of a fully resolved expression. Throws rather than guessing:
    * after `Builder.freeze` every literal carries a width, so a `None` here is
    * an elaborator defect (R21.7), not a user error. */
  def typeOf(e: Expr, types: SignalId => Type): Type =
    typeOpt(e, types).getOrElse(
      throw ExprTypeError(s"expression has no resolved width: $e"))

  /** The width of an expression, when knowable. */
  def widthOpt(e: Expr, types: SignalId => Type): Option[Int] =
    typeOpt(e, types).map(_.bitWidth)

  /** The width of a fully resolved expression. */
  def widthOf(e: Expr, types: SignalId => Type): Int =
    typeOf(e, types).bitWidth

  /** Whether an expression's value is signed — the question the emitter asks
    * per operation (R21.6). Only `SInt` answers yes; `Bits` is not a signedness
    * (R6) and a concatenation is a bit pattern (R21.5). */
  def isSigned(e: Expr, types: SignalId => Type): Boolean =
    typeOpt(e, types).exists(_.isInstanceOf[Type.SInt])

  /** R6.2 / R21.4 — whether an operand can carry a bit-selection: a named
    * signal, a bundle field or vec element of one, or a slice of either.
    * Narrowing `resize`, sign-extending `resize`, and signed `*` all emit a
    * bit-select and therefore require it; everything else composes over
    * arbitrary anonymous trees. */
  def isBitAddressable(e: Expr): Boolean = e match
    case Expr.Ref(_)             => true
    case Expr.Field(base, _)     => isBitAddressable(base)
    case Expr.Index(base, _)     => isBitAddressable(base)
    case Expr.Slice(base, _, _)  => isBitAddressable(base)
    case Expr.VarSlice(base, _, _) => isBitAddressable(base)
    case Expr.TypeCast(_, inner) => isBitAddressable(inner)  // emits transparently
    case Expr.Cdc(_, inner, _)   => isBitAddressable(inner)
    case _                       => false

  /** The same kind with a different width — the `resize` result rule (R21.4)
    * and the product width rule (R21.3). `Bool` is a 1-bit unsigned value, so
    * a width change makes it a `UInt`; every other scalar keeps its kind.
    * Aggregates have no width-changing operations (R21.4 non-goal) and are
    * returned unchanged so the width check reports the real error. */
  private def withWidth(t: Type, w: Int): Type = t match
    case Type.Bool         => if w == 1 then Type.Bool else Type.UInt(w)
    case Type.UInt(_)      => Type.UInt(w)
    case Type.SInt(_)      => Type.SInt(w)
    case Type.Bits(_)      => Type.Bits(w)
    case Type.GrayCode(_)  => Type.GrayCode(w)
    case Type.Clock        => Type.Clock
    case Type.Reset(p)     => Type.Reset(p)
    case b: Type.Bundle    => b
    case v: Type.Vec       => v

/** Raised when the IR carries a node the typer cannot type — an unknown
  * operator string, or an expression still holding an unresolved literal where
  * resolution was supposed to have run. Both are library defects rather than
  * user errors, so they fail loudly instead of degrading to a default
  * (R21.6: no silent fallback). */
final case class ExprTypeError(msg: String) extends RuntimeException(msg)

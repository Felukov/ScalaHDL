package scalahdl.core

import SignalId.*

/** R22 — Elaboration-time constant evaluation.
  *
  * A `switch` case value must name a bit pattern the elaborator can compute,
  * because that pattern is what duplicate detection compares and what the
  * backend writes as a case label. This is the one place that question is
  * answered, so the DSL, `SwitchPhase`, and any later consumer agree on what
  * "constant" means.
  *
  * The answer is a **bit pattern**, not a number: the result is masked to the
  * expression's own width (R21.6's shared typer supplies it), exactly as the
  * hardware would truncate it. That makes canonical identity — selector kind +
  * width + pattern — a plain `BigInt` comparison, and it makes two spellings
  * of the same bits (`3.U(2)` and `(1.U(2) | 2.U(2))`) the duplicates R22 says
  * they are.
  *
  * `None` means "not an elaboration-time constant", which is a user error at a
  * case value and simply a non-answer everywhere else. A signal reference is
  * never constant, not even one with a reset value: R17's IR has no notion of
  * a value a register happens to hold. */
object ExprConst:

  /** The constant bit pattern of `e`, masked to its own width, or `None` if it
    * depends on anything that is not known at elaboration time. */
  def bits(e: Expr, types: SignalId => Type): Option[BigInt] =
    def width(x: Expr): Option[Int] = ExprType.widthOpt(x, types)

    /** An operand's *value*: the same pattern read through its own type, so a
      * negative `SInt` participates in arithmetic as a negative number. */
    def value(x: Expr): Option[BigInt] =
      for
        b <- bits(x, types)
        w <- width(x)
      yield if ExprType.isSigned(x, types) then signed(b, w) else b

    e match
      case Expr.Lit(v, Some(w), _) => Some(mask(v, w))
      case Expr.Lit(_, None, _)    => None   // R6.1 — no width yet, so no pattern

      // Representation-preserving nodes: same bits, possibly a different type.
      case Expr.TypeCast(_, inner) => bits(inner, types)
      case Expr.Cdc(_, inner, _)   => bits(inner, types)

      case Expr.Resize(to, inner) =>
        // R21.4 — kind-preserving: widening an SInt replicates the sign bit,
        // which `signed` + `mask` do between them.
        for
          v <- value(inner)
          _ <- width(inner)
        yield mask(v, to)

      case Expr.Slice(base, hi, lo) =>
        bits(base, types).map(b => mask(b >> lo, hi - lo + 1))

      case Expr.Cat(parts) =>
        // R21.5 — parts left to right, each contributing its own width.
        parts.foldLeft(Option(BigInt(0))) { (acc, part) =>
          for
            a <- acc
            b <- bits(part, types)
            w <- width(part)
          yield (a << w) | b
        }

      case Expr.Repeat(n, inner) =>
        for
          b <- bits(inner, types)
          w <- width(inner)
        yield (0 until n).foldLeft(BigInt(0))((acc, _) => (acc << w) | b)

      case Expr.UnOp(op, arg) =>
        import ExprType.Op
        for
          b <- bits(arg, types)
          w <- width(arg)
          r <- (
            if Op.isComplement(op) then Some(mask(~b, w))
            else if Op.isNot(op) then Some(bit(b == 0))
            else if op == "|" then Some(bit(b != 0))
            else if op == "&" then Some(bit(b == mask(-1, w)))
            else if op == "^" then Some(bit(b.bitCount % 2 == 1))
            else None
          )
        yield r

      case Expr.BinOp(op, lhs, rhs) =>
        import ExprType.Op
        for
          l <- value(lhs)
          r <- value(rhs)
          w <- width(e)
          out <- binOp(op, l, r, lhs, rhs, width, types).map(mask(_, w))
        yield out

      // A signal's value is not an elaboration-time fact (R17), and neither is
      // a selection from one; a `Mux` is hardware by construction.
      // R34 — a runtime-offset window is not a constant even if its base has
      // the pattern: the offset decides which bits appear.
      case Expr.Ref(_) | Expr.Index(_, _) | Expr.Field(_, _) | Expr.Mux(_, _, _)
         | Expr.VarSlice(_, _, _) => None

  /** Apply one binary operator to two already-interpreted operand values. The
    * result is masked by the caller, so this works in unbounded `BigInt` and
    * lets truncation happen once, at the width the typer gives the node. */
  private def binOp(op: String, l: BigInt, r: BigInt, lhs: Expr, rhs: Expr,
                    width: Expr => Option[Int],
                    types: SignalId => Type): Option[BigInt] =
    import ExprType.Op
    if Op.isShift(op) then
      // R21.1 — the amount is unsigned, and shifting past the operand's width
      // cannot reveal anything a shift by `w + 1` does not already show, so it
      // is clamped rather than handed to `BigInt` as an allocation request.
      val w = width(lhs).getOrElse(1)
      val amount = (r max BigInt(0)).min(BigInt(w + 1)).toInt
      val pattern = ExprConst.bits(lhs, types).getOrElse(BigInt(0))
      op match
        case "<<"  => Some(pattern << amount)
        case ">>"  => Some(pattern >> amount)          // logical, on the pattern
        case ">>>" => Some(l >> amount)                // arithmetic, on the value
        case _     => None
    else op match
      case "+" => Some(l + r)
      case "-" => Some(l - r)
      case "*" => Some(l * r)
      case "&" => Some(l & r)
      case "|" => Some(l | r)
      case "^" => Some(l ^ r)
      case "==" => Some(bit(l == r))
      case "!=" => Some(bit(l != r))
      case "<"  => Some(bit(l < r))
      case "<=" => Some(bit(l <= r))
      case ">"  => Some(bit(l > r))
      case ">=" => Some(bit(l >= r))
      case _    => None

  /** Two's-complement truncation to `w` bits — what the hardware does. */
  def mask(v: BigInt, w: Int): BigInt =
    if w <= 0 then BigInt(0) else v & ((BigInt(1) << w) - 1)

  /** The signed reading of a `w`-bit pattern. */
  def signed(pattern: BigInt, w: Int): BigInt =
    if w > 0 && pattern.testBit(w - 1) then pattern - (BigInt(1) << w) else pattern

  private def bit(b: Boolean): BigInt = if b then BigInt(1) else BigInt(0)

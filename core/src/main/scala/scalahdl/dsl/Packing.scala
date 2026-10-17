package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module
import SignalId.*

/** R07.2 — Packing follows SystemVerilog packed structs. The first-declared
  * field occupies the most significant bits; the last the least. A bundle is
  * bit-equivalent to the concatenation of its fields in declaration order.
  * `Vec` packs descending — element *n-1* in the high bits, matching a
  * SystemVerilog packed array declared `[n-1:0]`.
  *
  * **Reading needs no operation.** An aggregate *is* that word, so it reads as
  * one wherever an expression is expected — `Cat(pair, pair)`, `Mux(sel, a, b)`,
  * `a === b` — and `pair.asBits` is R21.4's ordinary cast asking for it
  * explicitly. `AggregateRead` writes the concatenation, once, for every read
  * alike; the layout it uses is the one this rule states.
  *
  * **Writing keeps one**, and this file is it. Bits become a bundle through
  * `unpackBits` and nowhere else, because that is the direction where the
  * shapes can disagree: a packed word carries no field names, and letting one
  * land in an aggregate by width alone is exactly the SystemVerilog behaviour
  * R14 exists to refuse.
  *
  * The layout is what the SystemVerilog packed-struct backend (R09) honours;
  * both V2001 and SV agree bit-for-bit. */

/** `unpackBits(target, bits)` — unpack a packed word into a Bundle/Vec
  * signal's fields, slicing per R07.2 layout (first field in the MSBs). Lowers
  * to per-field `Slice` assignments — no generated signals (R2). The target
  * must be a `DslSignal` whose `typeOf` is an aggregate; `bits` must be a
  * named signal (R6.2 — `Slice` takes a `SignalId`). */
def unpackBits(target: DslSignal, bits: Expr)(using m: Module): Unit =
  val base = sliceTarget(bits)
  unpackType(target, target.typeOf, base, hi = target.typeOf.bitWidth - 1)

/** Recursive unpacking: assign slices of `bits` to `target`'s fields. The
  * `hi` cursor tracks the current high-bit position (fields pack first-high,
  * so the first field takes the top `fieldWidth` bits). The original `bitsId`
  * is threaded unchanged; only the scalar leaf emits a `Slice(bitsId, hi, lo)`. */
private def unpackType(target: DslSignal, t: Type, bitsBase: Expr, hi: Int)(using m: Module): Int =
  t match
    case Type.Bundle(fields) =>
      // First field in MSBs: walk fields in order, decrementing the cursor.
      fields.foldLeft(hi) { (h, fn_ft) =>
        val (fn, ft) = fn_ft
        val leaf = target.field(fn)
        val w = ft.bitWidth
        unpackType(leaf, ft, bitsBase, h)
        h - w
      }
    case Type.Vec(n, elem) =>
      // Descending: element n-1 in the high bits → assign n-1 down to 0.
      (n - 1 to 0 by -1).foldLeft(hi) { (h, i) =>
        val leaf = target(i)
        val w = elem.bitWidth
        unpackType(leaf, elem, bitsBase, h)
        h - w
      }
    case scalar =>
      // A scalar leaf: assign the slice of `bits` to it. A `Slice` is typed
      // `UInt` (R6.2), so a field of any other kind needs the same
      // representation-preserving cast `packBits` uses on the way out, or R6.5
      // would reject the library's own unpacking. Emission is transparent, and
      // a `UInt` field is left alone so the common case keeps its exact IR.
      val w = scalar.bitWidth
      val slice = Expr.Slice(bitsBase, hi, hi - w + 1)
      val value = scalar match
        case _: Type.UInt => slice
        case other        => Expr.TypeCast(other, slice)
      // `Assign.at(…, NoLoc)` rather than `:=`, which would capture *this*
      // line: an assignment the library assembles has no user call site to
      // name, and these slices are cut to the field's own width, so a failure
      // here would be a library defect rather than a caller's mistake.
      Assign.at(target, value, Loc.NoLoc)
      hi - w

/** Extract the `SignalId` from a packed-word expression (a `Ref` or a `Field`/
  * `Index` of a `Ref`). `Slice` requires a `SignalId`, so `unpackBits` requires
  * its `bits` argument to be a named signal (R6.2). */
private def sliceTarget(e: Expr): Expr = e match
  case r @ Expr.Ref(_)        => r
  case f @ Expr.Field(base, _) => sliceTarget(base); f
  case i @ Expr.Index(base, _) => sliceTarget(base); i
  case _ => throw IllegalArgumentException(
    "unpackBits requires a named `bits` signal or selected sub-element: bind the expression to a `val` first")

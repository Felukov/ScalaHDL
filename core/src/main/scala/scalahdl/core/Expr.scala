package scalahdl.core

import SignalId.*

/** R17 — Expression. A tree with no identity, never declared, inlined at every
  * use site (R2). Wire count in the emitted Verilog equals named-`val` count;
  * anonymous trees never receive a declaration.
  *
  * R6.2 is enforced structurally here: a slice of an anonymous tree cannot be
  * expressed in the IR, so no backend or future pass can violate it.
  *
  * R14: `Field` and `Index` take an `Expr` base so chained aggregate access
  * (`vec(i).field`) is expressible. The base must bottom out at an `Expr.Ref`
  * to a named signal — the DSL enforces this by only building `Field`/`Index`
  * off a `DslSignal` (a named handle), so the structural R6.2 guarantee is
  * preserved: a field/index of an anonymous tree still cannot be expressed. */
enum Expr:
  case Ref(id: SignalId)
  /** R6.1/R6.4 — a constant, with two independently inferable properties.
    *
    * `width` is `None` until R6.1/R6.3 resolution gives it one from context.
    * `kind` is `None` for a bare `Int`, which has no kind of its own and takes
    * the context's at the same moment and from the same place (R6.4); `.U` and
    * `.S` fix it at the literal, and a fixed kind is never overridden.
    *
    * Both are `Some` after `Builder.freeze`, which is why `ExprType.typeOf`
    * can throw rather than guess. */
  case Lit(value: BigInt, width: Option[Int], kind: Option[LitKind])
  case UnOp(op: String, arg: Expr)
  case BinOp(op: String, lhs: Expr, rhs: Expr)
  case Mux(sel: Expr, whenTrue: Expr, whenFalse: Expr)
  case Slice(base: Expr, hi: Int, lo: Int)
  /** R34 — a window whose low bit is a runtime value: `base[idx +: width]`.
    *
    * The width is a compile-time constant, the offset is not, which is what
    * distinguishes it from `Slice` (both constant) and from `Index` (a single
    * bit). Verilog-2001 spells it with the indexed part-select `+:`, so it
    * reads back as written (R12). The base must bottom out at a named signal
    * exactly as `Slice`/`Index` do (R6.2). */
  case VarSlice(base: Expr, lo: Expr, width: Int)
  case Index(base: Expr, idx: Expr)
  case Field(base: Expr, name: String)
  case Cat(parts: List[Expr])
  /** R21.4 — Width-changing, kind-preserving resize. Distinct from `TypeCast`,
    * which preserves the representation and emits transparently: a `Resize`
    * emits a bit-selection when narrowing and a zero/sign extension when
    * widening, and the extension policy is read off the operand's type rather
    * than chosen at the call site. */
  case Resize(to: Int, expr: Expr)
  /** R21.5 — Replication, `{n{x}}`. Backs `Fill(n, x)` and the sign-extension
    * half of a widening `Resize`, so neither has to spell an *n*-fold `Cat`. */
  case Repeat(n: Int, expr: Expr)
  /** R05.3 — Explicit representation-preserving type conversion. */
  case TypeCast(to: Type, expr: Expr)
  /** R05 — An explicit blessed or justified crossing. The marker is retained
    * in IR so validation, reporting, and emission observe the same construct. */
  case Cdc(primitive: CdcPrimitive, expr: Expr, loc: Loc)

object Expr:
  /** A fully-fixed unsigned constant. The spelling for a value that comes from
    * a host parameter rather than a source literal, where `.U` is not
    * available: `Expr.uint(config.resetAddress, 32)`. */
  def uint(value: BigInt, width: Int): Expr =
    Lit(value, Some(width), Some(LitKind.Unsigned))

  /** A fully-fixed signed constant, the `.S` counterpart of `uint`. */
  def sint(value: BigInt, width: Int): Expr =
    Lit(value, Some(width), Some(LitKind.Signed))

/** R6.4 — the kind a literal carries, once it has one.
  *
  * The four bit-typed kinds a literal can land in, named after the `Type`s they
  * become. `.U` and `.S` fix `Unsigned` and `Signed` at the literal; `Bits` and
  * `Bool` have no suffix and are reachable only by a bare `Int` taking its kind
  * from context, which is why `bits === 5.U` was an error with no spelling that
  * worked before R6.4. */
enum LitKind:
  case Unsigned, Signed, Bits, Bool

object LitKind:
  /** The kind a literal must take to sit in a context of type `t`, or `None`
    * where the type is not one a literal can inhabit (an aggregate, a clock,
    * a reset — R7.2/R13.1). `GrayCode` is excluded by R5.3: it admits only its
    * preserving operations, so a literal never becomes one. */
  def of(t: Type): Option[LitKind] = t match
    case _: Type.UInt => Some(Unsigned)
    case _: Type.SInt => Some(Signed)
    case _: Type.Bits => Some(Bits)
    case Type.Bool    => Some(Bool)
    case _            => None

  /** The `Type` a literal of this kind and width has. */
  def typeOf(kind: LitKind, width: Int): Type = kind match
    case Unsigned => Type.UInt(width)
    case Signed   => Type.SInt(width)
    case Bits     => Type.Bits(width)
    case Bool     => if width == 1 then Type.Bool else Type.UInt(width)

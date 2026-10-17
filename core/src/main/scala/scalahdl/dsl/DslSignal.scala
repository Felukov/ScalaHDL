package scalahdl.dsl

import scalahdl.core.*
import SignalId.*
import scalahdl.dsl.BitSelectAnonymousError
import scalahdl.diag.{Code, Diagnostic, RelatedLoc, Severity, ValidationFailed}

/** A handle to a signal in the IR, returned by the hardware factories
  * (`UInt`, `Reg`, `In`, `Out`). It carries the `SignalId` so expressions and
  * assignments reference the signal by id (R17.1), and the `Type` so literals
  * and operators can take their width from context (R6.1).
  *
  * The handle is also an expression context: `a + b`, `a := b`, `when(a)`
  * all operate on `DslSignal` and produce `Expr`s or record statements into
  * the builder.
  *
  * A `DslSignal` auto-refs to an `Expr` in expression contexts via the given
  * conversion below, so `count := cnt` and `cnt + 1` read naturally.
  *
  * R14: a `DslSignal` may name a sub-element of an aggregate — a bundle
  * field or a vec element — via its `path`, and R6.7 adds a third kind of
  * step, a constant window of a bit-vector. The root `id` is the aggregate
  * signal; `path` records the field/index chain. `ref` builds the chained
  * `Expr`; `toLhs` builds the chained `Lhs`. A scalar `DslSignal` has
  * `path = Nil` and behaves exactly as before.
  *
  * Bundle field leaves are built *unbound* (placeholder id `SignalId(-1)`)
  * during bundle construction, then `rebind` sets the root id once the bundle
  * is bound to a signal (R07). This is the post-construction rebind pattern
  * that keeps fields as real `val`s (tool-resolvable symbols) while letting a
  * bundle blueprint (`Req(8)`) become a signal handle (`In(Req(8))`). The
  * mutable fields are internal; a bound handle is, for all user purposes,
  * a stable reference. */
final class DslSignal(private var _id: SignalId, private var _name: String,
                      val typeOf: Type, private var _isReg: Boolean,
                      private var _path: List[DslSignal.PathSeg] = Nil):
  import DslSignal.*

  /** The root signal id this handle references (R17.1). `SignalId(-1)` while
    * unbound (a bundle blueprint leaf before rebind). */
  def id: SignalId = _id
  /** The root signal's name. The leaf's own name while unbound. */
  def name: String = _name
  /** Whether the root signal is a register (R14.2). */
  def isReg: Boolean = _isReg
  /** The sub-element path (field/index segments). `Nil` for a scalar. */
  def path: List[PathSeg] = _path

  /** R18.1 — Update the root name after an explicit external-port override. */
  private[scalahdl] def renameRoot(newName: String): Unit = _name = newName

  /** R18/R23 — An input port on an instantiated child records a named instance
    * connection instead of becoming an ordinary assignment target. Blackboxes
    * and generated modules share this binding. */
  private var _instanceInput: Option[(scalahdl.elab.Builder, Int, String)] = None
  private[scalahdl] def bindInstanceInput(parent: scalahdl.elab.Builder,
                                          instanceIndex: Int, portName: String): Unit =
    _instanceInput = Some((parent, instanceIndex, portName))
  private[scalahdl] def instanceInput: Option[(scalahdl.elab.Builder, Int, String)] =
    _instanceInput

  /** R23 — The module that declared this signal. `:=` compares it against the
    * assigning module so a write to a foreign module's signal is a named error
    * rather than a silent write to whichever local signal shares the id. */
  private var _owner: Option[scalahdl.elab.Builder] = None
  private[scalahdl] def bindOwner(builder: scalahdl.elab.Builder): Unit =
    _owner = Some(builder)
  private[scalahdl] def owner: Option[scalahdl.elab.Builder] = _owner

  /** R23 — An output port of an instantiated child, rebound to the parent-side
    * net the instance drives. Reading it is the point; assigning to it would be
    * a second driver on an instance-driven net (R12). Carries the instance name
    * so the diagnostic can say which instance already drives it. */
  private var _instanceOutput: Option[String] = None
  private[scalahdl] def bindInstanceOutput(instanceName: String): Unit =
    _instanceOutput = Some(instanceName)
  private[scalahdl] def instanceOutput: Option[String] = _instanceOutput

  /** R23 — a sub-handle carries its root's provenance.
    *
    * A field or element handle is a *new* handle holding the root's id and one
    * more path step, and the three facts that say where the signal came from
    * live on the handle rather than on the id. Without copying them, `u.in.f`
    * knows only the id and the path: an assignment to it misses the instance
    * routing, falls through to the ordinary path, and is recorded in the
    * assigning module under the *other* module's signal id — the wrong-module
    * write this rule exists to make impossible. */
  private def inheriting(sub: DslSignal): DslSignal =
    _owner.foreach(sub.bindOwner)
    _instanceInput.foreach { case (parent, index, portName) =>
      sub.bindInstanceInput(parent, index, portName) }
    _instanceOutput.foreach(sub.bindInstanceOutput)
    sub

  /** Rebind this leaf to a root signal and complete path. */
  def bindPath(rootId: SignalId, rootName: String, isReg: Boolean,
               completePath: List[PathSeg]): Unit =
    _id = rootId
    _name = rootName
    _isReg = isReg
    _path = completePath

  /** Compatibility helper for binding one additional segment. */
  def rebind(rootId: SignalId, rootName: String, isReg: Boolean, seg: PathSeg): Unit =
    bindPath(rootId, rootName, isReg, _path :+ seg)

  /** Reference this signal in an expression (R17.1 — by id, not by object).
    * For a sub-element handle, builds the chained `Field`/`Index`/`Slice` off
    * the root `Ref`. */
  /** R23 — reading a signal as a value.
    *
    * An instance's *input* is the one handle this must refuse. `:=` on it does
    * not assign, it records a connection on the parent's instance (R18.2/R23),
    * so the child's id is never rebound to anything the parent owns — and
    * signal ids are per-module. A read would therefore build `Expr.Ref` of the
    * child's id inside the parent, where it names whichever local signal
    * happens to share the number: the child's third port reads back as the
    * parent's third port. That is the same hazard `FOREIGN_SIGNAL_ASSIGNED`
    * refuses on the write side, and it is worse here, because a wrong
    * *assignment* has a chance of being noticed and a wrong *read* elaborates,
    * emits and simulates as ordinary logic.
    *
    * The check needs no module context: the binding is set by `addInstance`,
    * which runs after the child's own body has elaborated, so a handle that
    * carries one can only be being read by the parent. */
  def ref: Expr =
    instanceInput.foreach { case (_, _, portName) =>
      throw ValidationFailed.one(Diagnostic(
        severity = Severity.Error,
        code = Code.INSTANCE_INPUT_READ,
        summary = s"`$portName` is an input of an instance and cannot be read",
        primaryLoc = Loc.NoLoc,
        related = List(RelatedLoc(Loc.NoLoc,
          "signal ids are per-module, so this would otherwise read an unrelated signal of the reading module")),
        fix = Some(s"read the value you drove it with: name it — `val w = Wire(...); w := expr; u.$portName := w` — and read `w`")))
    }
    path.foldLeft[Expr](Expr.Ref(id)) { (base, seg) =>
      seg match
        case PathSeg.Field(name)      => Expr.Field(base, name)
        case PathSeg.Index(idxExpr)   => Expr.Index(base, idxExpr)
        case PathSeg.Slice(hi, lo)    => Expr.Slice(base, hi, lo)
        case PathSeg.VarSlice(lo, w)  => Expr.VarSlice(base, lo, w)
    }

  /** The `Lhs` for an assignment target (R14, R6.7). `Sig` for a scalar, a
    * chained `Lhs.Field`/`Lhs.Index`/`Lhs.Slice` for a sub-element. */
  def toLhs: Lhs =
    path.foldLeft[Lhs](Lhs.Sig(id)) { (base, seg) =>
      seg match
        case PathSeg.Field(name)      => Lhs.Field(base, name)
        case PathSeg.Index(idxExpr)   => Lhs.Index(base, idxExpr)
        case PathSeg.Slice(hi, lo)    => Lhs.Slice(base, hi, lo)
        case PathSeg.VarSlice(lo, w)  => Lhs.VarSlice(base, lo, w)
    }

  /** R6.7 — whether this handle is already a window. Verilog has no selection
    * inside a part-select, so a slice is the last step a path can take and a
    * further `apply` is R6.2's anonymous-selection error rather than a
    * composition. Composing the two windows would be writable — the emitter
    * already folds `Expr.Slice` of `Expr.Slice` into one absolute selection —
    * but it was an error before R6.7 and staying one keeps the surface where
    * it was: bind a `val` and select once. */
  private def isWindow: Boolean = path.lastOption.exists {
    case _: PathSeg.Slice    => true
    case _: PathSeg.VarSlice => true
    case _                   => false
  }

  /** Width of this signal's type. */
  def width: Int = typeOf.bitWidth

  /** R6.2 / R6.7 — `sig(hi, lo)`, a contiguous window of a named signal.
    *
    * Returns a sub-handle, exactly as `sig(i)` does for one bit, so the window
    * reads as an `Expr` through the conversion below *and* is an `:=` target.
    * Before R6.7 this returned a bare `Expr` and the asymmetry was the bug:
    * `x(3) := b` compiled and `x(7, 0) := d` did not, though Verilog spells
    * both as one part-select and a design that needs one usually needs the
    * other.
    *
    * The window is constant, the operand must be bit-addressable, and the
    * result is unsigned of the window's width — the same three properties
    * `Expr.Slice` has on the reading side, and the emitted selection is
    * byte-identical to what this produced before. Selecting *within* a window
    * stays R6.2's error (see [[isWindow]]). */
  def apply(hi: Int, lo: Int): DslSignal =
    if isWindow then throw BitSelectAnonymousError("bit-slice")
    require(hi >= lo,
      s"a part-select runs from its most significant bit down: ($hi, $lo) is " +
      s"reversed, and the window it names has no width")
    typeOf match
      case _: Type.UInt | _: Type.SInt | _: Type.Bits | _: Type.GrayCode | Type.Bool => ()
      case other =>
        throw IllegalArgumentException(
          s"slicing a $other is not supported — a window is a run of bits, and " +
          "an aggregate's bits are a concatenation, which Verilog-2001 cannot " +
          "select inside; bind `x.asBits` to a `val` and select that")
    inheriting(new DslSignal(id, name, Type.UInt(hi - lo + 1), isReg,
                             path :+ PathSeg.Slice(hi, lo)))

  /** `sig(i)` — index by a constant (R6.2 for scalars, R14 for Vecs). Returns a
    * sub-handle whose `path` carries the constant index:
    *
    *   - on a `Vec`: an element sub-handle (R14); `entries(2).addr` type-checks
    *     because the returned handle's `typeOf` is the Vec's element type.
    *   - on a scalar `UInt`/`SInt`/`Bits`: a single-bit select (R6.2); the
    *     sub-handle's `typeOf` is `Bool` (1 bit) and `ref` builds `Expr.Index`.
    *
    * A sub-handle reads as an `Expr` via the `DslSignal → Expr` conversion and
    * is an `:=` target as a `DslSignal`. Constant-index reads on a Vec emit as
    * the per-element leaf name (port) or a net-array read (internal);
    * constant-index writes are legal on both Reg and wire Vecs (R14.2). */
  def apply(i: Int): DslSignal =
    if isWindow then throw BitSelectAnonymousError("bit-index")
    val elemType = typeOf match
      case Type.Vec(_, elem)       => elem
      case _: Type.UInt | _: Type.SInt | _: Type.Bits | _: Type.GrayCode => Type.Bool
      case other =>
        throw IllegalArgumentException(s"indexing a $other with a constant is not supported")
    inheriting(new DslSignal(id, name, elemType, isReg, path :+ PathSeg.Index(constIdxExpr(i))))

  /** Index with a runtime index (a named signal). Returns a sub-handle whose
    * `path` carries the runtime index expression.
    *
    * On a `Vec` the read lowers to a native Verilog array read (R14.1), and a
    * write requires a `Reg` Vec (R14.2) — the check is in `VecWritePhase`.
    * On a scalar `UInt`/`SInt`/`Bits`/`GrayCode` it is R34's runtime
    * bit-select: the `typeOf` is `Bool` and the read lowers to `word[i]`. */
  def apply(i: DslSignal): DslSignal =
    if isWindow then throw BitSelectAnonymousError("bit-index")
    val elemType = typeOf match
      case Type.Vec(_, elem) => elem
      case _: Type.UInt | _: Type.SInt | _: Type.Bits | _: Type.GrayCode => Type.Bool
      case other =>
        throw IllegalArgumentException(s"indexing a non-Vec/non-bit-vector type $other with a runtime index")
    inheriting(new DslSignal(id, name, elemType, isReg, path :+ PathSeg.Index(i.ref)))

  /** R34 — `word(i, w)`, a window of `w` bits starting at the runtime offset
    * `i`. The width is a constant (the result's type is `UInt(w)`, and Verilog
    * needs a constant width for an indexed part-select); the offset is not.
    * Emits `word[i +: w]`, which reads back as written (R12).
    *
    * A runtime window is a window: like `sig(hi, lo)` it is the last step of a
    * path, and selecting within it is R6.2's error ([[isWindow]]). The
    * operand must be a bit-vector; a `Vec` element has no part-select in
    * Verilog-2001 (R6.7/R14.1). Only a scalar bit-vector is accepted today. */
  def apply(i: DslSignal, w: Int): DslSignal =
    if isWindow then throw BitSelectAnonymousError("bit-slice")
    require(w > 0, s"a window of $w bits has no width")
    typeOf match
      case _: Type.UInt | _: Type.SInt | _: Type.Bits | _: Type.GrayCode => ()
      case other =>
        throw IllegalArgumentException(
          s"a runtime part-select of $other is not supported — a window is a " +
          "run of bits")
    inheriting(new DslSignal(id, name, Type.UInt(w), isReg,
                             path :+ PathSeg.VarSlice(i.ref, w)))

  /** Bind a freshly-created Bundle view to this aggregate sub-handle. This is
    * the typed bridge used by Vec indexing: `entries(2).as(Entry()).addr` keeps
    * real Scala field symbols while preserving the index path. */
  def as[B <: Bundle](factory: => B): B =
    val b = factory
    if b.toType != typeOf then
      throw IllegalArgumentException(
        s"bundle view has type ${b.toType}, but selected value has type $typeOf")
    b.rebind(this)
    b

  /** Programmatic bundle field access, returning a sub-handle sharing the root
    * id. Retained for generic library code; statically known Bundle elements
    * should use `as(factory).fieldVal` to preserve typed field access. */
  def field(fieldName: String): DslSignal =
    val ftype = typeOf match
      case Type.Bundle(fields) =>
        fields.find(_._1 == fieldName).map(_._2).getOrElse(
          throw IllegalArgumentException(s"bundle has no field `$fieldName`"))
      case other =>
        throw IllegalArgumentException(s"`.field` on a non-bundle type $other")
    inheriting(new DslSignal(id, name, ftype, isReg, path :+ PathSeg.Field(fieldName)))

/** A `DslSignal` is an `Expr` in any expression context (`a + b`, `when(a)`,
  * `x := a`). It is the conversion the DSL is built on; it keeps the
  * call site `count := cnt` free of `.ref` noise. */
given Conversion[DslSignal, Expr] = s => s.ref

object DslSignal:
  /** One segment of a sub-element path: a bundle field name or a vec index
    * expression. The index is an `Expr` so it may be a literal (constant index)
    * or a `Ref` (runtime index — R14.2). */
  enum PathSeg:
    case Field(name: String)
    case Index(idx: Expr)
    /** R6.7 — a constant window. Constant rather than an `Expr` because a
      * part-select's bounds are constant in Verilog-2001, and because the
      * handle's own `typeOf` is the window's width, which a runtime bound
      * could not supply. */
    case Slice(hi: Int, lo: Int)
    /** R34 — a window of a constant width at a runtime offset: `base[lo +: w]`.
      * The offset is an `Expr` (a runtime value); the width is a constant, so
      * the handle's `typeOf` is `UInt(w)` exactly as [[Slice]]'s is. */
    case VarSlice(lo: Expr, width: Int)

  /** Build a constant-index `Expr` for a Vec element path segment. */
  private def constIdxExpr(i: Int): Expr =
    Expr.uint(BigInt(i), 32)

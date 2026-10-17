package scalahdl.dsl

import scalahdl.core.*
import SignalId.*
import scala.collection.mutable

/** R07 — Direction-free bundles. A `Bundle` is a named, ordered group of
  * fields; fields carry no direction (direction is applied at the port, R10,
  * uniformly to all leaves).
  *
  * A `Bundle` instance serves two roles:
  *
  *   1. **Type blueprint** — `Req(8).toType` yields `Type.Bundle(...)` for
  *      `In`/`Out`/`Reg`, which store the direction-free aggregate type.
  *   2. **Signal handle** — its fields are real Scala `val`s returning
  *      `DslSignal` sub-handles, so `req.addr` resolves `addr` as a genuine
  *      symbol (jump-to-definition, autocomplete, refactor — the R1
  *      correspondence the library demands).
  *
  * Construction. The field `val`s of a `case class Req extends Bundle` build
  * *unbound* `DslSignal` leaves (with a placeholder id) during construction.
  * `toType` reads each leaf's `typeOf`. When the bundle is bound to a signal
  * (`In`/`Out`/`Reg` allocate the signal, then rebind the leaves), the leaves'
  * root id / name / isReg are set, so `req.addr` yields a sub-handle that
  * references the real signal. The leaves are `val`s (tool-resolvable) but
  * carry a settable binding internally — the same post-construction rebind
  * pattern Chisel `Record` and SpinalHDL `Bundle` use. Field order is `val`
  * evaluation order (declaration order), feeding R07.2 packing. */
abstract class Bundle:
  import Bundle.*

  /** The signal this bundle is bound to, set by `In`/`Out`/`Reg`. `None` for a
    * blueprint. */
  private var wholeHandle: Option[DslSignal] = None

  /** **The bundle as one signal**, rather than as its fields — the handle the
    * two conversions below are defined in terms of.
    *
    * Internal. It was on the surface for as long as a read of a whole bundle
    * had to name it, and the conversions ended that: a bundle is a `DslSignal`
    * and an `Expr` wherever either is expected, so `out := in`, `u.req := req`,
    * `Cat(pair, pair)` and `pair.asBits` all say what they mean without it. A
    * pin is reached through the field `val`s — `req.addr` — as it always was.
    *
    * Available once the bundle is bound to a signal. */
  private[scalahdl] def whole: DslSignal = wholeHandle.getOrElse(
    throw IllegalStateException("bundle is not bound to a signal"))

  private val _fields = mutable.ListBuffer.empty[(String, DslSignal)]

  /** The collected `(name, leaf)` pairs in declaration order. The leaf's
    * `typeOf` gives the field type for `toType`. */
  def fieldLeaves: List[(String, DslSignal)] = _fields.toList

  /** The direction-free aggregate type. */
  def toType: Type.Bundle =
    Type.Bundle(_fields.toList.map { case (n, l) => (n, l.typeOf) })

  /** Rebind this bundle's leaves to a signal: set each leaf's root id, name,
    * and `isReg`, and push `Field(name)` onto its path. Called by
    * `bundleSignal` after the signal is allocated. Leaves share the root id;
    * the path distinguishes them. Also stashes the whole-bundle handle. */
  def rebind(root: DslSignal): Unit =
    wholeHandle = Some(root)
    _fields.foreach { case (name, leaf) =>
      // Preserve the complete path when a Bundle is itself a Vec element or a
      // nested field. Dropping `root.path` would incorrectly turn
      // `entries(2).addr` into `entries.addr`.
      leaf.bindPath(root.id, root.name, root.isReg,
        root.path :+ DslSignal.PathSeg.Field(name))
      // R23 — a leaf follows its root's provenance, all three of it. Without
      // the owner a leaf carries no module, and assigning one across a module
      // boundary would be recorded against the other module's id instead of
      // rejected; without the other two, a leaf of a child's port would not
      // know it is one, and `u.in.field := expr` would be an assignment where
      // it should be a connection.
      root.owner.foreach(leaf.bindOwner)
      root.instanceInput.foreach { case (parent, index, portName) =>
        leaf.bindInstanceInput(parent, index, portName) }
      root.instanceOutput.foreach(leaf.bindInstanceOutput)
    }

  /** R23 — mark every leaf as driven by an instance, after the root has been
    * rebound to a parent-side net. Reading a leaf is the point; assigning one
    * would be a second driver on an instance-driven net (R12). */
  private[scalahdl] def markLeavesDrivenBy(instanceName: String): Unit =
    _fields.foreach { case (_, leaf) => leaf.bindInstanceOutput(instanceName) }

  /** R23 — mark every leaf as a leaf of an instance's *input* port, so that
    * assigning one records a connection on that pin rather than a statement.
    *
    * The root learns this when the parent instantiates the child, which is
    * long after these leaves were bound; they are reached from there. */
  private[scalahdl] def markLeavesInstanceInput(
      parent: scalahdl.elab.Builder, index: Int, portName: String): Unit =
    _fields.foreach { case (_, leaf) => leaf.bindInstanceInput(parent, index, portName) }

  // ---- field spellers (R1: name captured via Source.capture) ----
  //
  // Each builds an *unbound* `DslSignal` leaf (placeholder id) carrying the
  // field's type. `toType` reads the type; `rebind` sets the root id later.
  // The leaf is a real `val` so `req.addr` is a tool-resolvable symbol.
  //
  // `Source.capture` is inlined *directly* into each speller (one level) so the
  // splice owner resolves to the user's field `val` (`addr`, `prot`, …).
  // Going through an intermediate inline def breaks the owner chain.

  protected inline def UInt(w: Int): DslSignal = recordLeaf(Source.capture, Type.UInt(w))
  protected inline def Bits(w: Int): DslSignal = recordLeaf(Source.capture, Type.Bits(w))
  protected inline def Bool(): DslSignal = recordLeaf(Source.capture, Type.Bool)
  protected inline def SInt(w: Int): DslSignal = recordLeaf(Source.capture, Type.SInt(w))

  /** `val inner = field(InnerBundle())` — a nested bundle, or a Vec field
    * (`field(Vec(4, UInt(8)))`). The element is any `Type` (including
    * `Type.Bundle`/`Type.Vec`); for a nested `Bundle` instance, its `toType`
    * is the field type. Returns a `DslSignal` leaf so the field is a real
    * `val`. */
  protected inline def field(t: Type): DslSignal = recordLeaf(Source.capture, t)

  /** Register an unbound leaf from a captured source + type. Inline so
    * `Source.capture`'s splice owner resolves to the user's field `val`. */
  private inline def recordLeaf(src: Source, t: Type): DslSignal =
    val name = src.name.getOrElse(
      throw BundleFieldAnonymous(t, src.loc))
    val leaf = new DslSignal(SignalId(-1), name, t, false, Nil)
    _fields += (name -> leaf)
    leaf

/** A `Bundle` is a `Type` in any context expecting one (e.g. `In(Req(8))`,
  * `Reg(Req(8))`), via its `toType`. Direction is applied at the port; the
  * bundle instance is directionless. */
given Conversion[Bundle, Type] = _.toType

/** A bound `Bundle` is a `DslSignal` — the signal it is bound to — wherever one
  * is expected, so `req := other` assigns the whole bundle and field access
  * (`req.addr`) still uses the bundle's real `val`s. */
given Conversion[Bundle, DslSignal] = _.whole

/** ...and it is an `Expr` wherever *that* is expected, which is what lets a
  * bundle be read:
  *
  * {{{
  *   u.req := request        // a port connection
  *   Cat(pair, pair)         // ...and an ordinary expression (R07.2)
  * }}}
  *
  * Two conversions are needed rather than one because Scala will not chain
  * them: `Bundle → DslSignal → Expr` is two steps, and every read of a bundle
  * wants the second.
  *
  * What the read *means* is R07.2's, not this conversion's. A bundle is a
  * packed vector of bits, so it reads as one — `AggregateRead` writes the
  * concatenation for every read alike, and the assignments and connections
  * that carry an aggregate whole keep it. An aggregate arriving at a scalar
  * target is therefore a width or kind question like any other, and bits
  * arriving at an aggregate one is still R14's shape error. */
given Conversion[Bundle, Expr] = _.whole.ref

object Bundle:
  /** Thrown when a bundle field's `val` is not bound to a name (R1). */
  final case class BundleFieldAnonymous(t: Type, loc: Loc)
      extends RuntimeException(
        s"anonymous bundle field of type $t has no name; bind it to a `val`")

  /** Build a signal-bound bundle: allocate the signal (via `alloc`), construct
    * the bundle, rebind its leaves to the signal, and return the bundle. The
    * bundle's field `val`s are real Scala symbols (tool-resolvable, R1);
    * the bundle is a `DslSignal` and an `Expr` through the conversions below.
    *
    * `alloc` is the factory that declares the signal in the IR (`addPort`/
    * `addWire`/`addReg`) and returns the root `DslSignal`. The bundle is
    * constructed *after* the signal exists so `rebind` can wire the leaves. */
  def bind[B <: Bundle](alloc: => DslSignal, factory: => B): B =
    val root = alloc
    attach(root, factory)

  /** R31 — the second half of [[bind]], for a bundle that already exists. An
    * interface field's bundle is constructed with the interface, before the
    * port it will be bound to has a name; when the endpoint is declared the
    * port is allocated and the same instance is attached here, so the field's
    * `val` is the port. Everything [[bind]] registers, this registers. */
  def attach[B <: Bundle](root: DslSignal, b: B): B =
    b.rebind(root)
    // R23 — record how to re-point this bundle's leaves. A Bundle port bound
    // as a submodule output has its root rebound to a parent-side net, and the
    // leaves must follow or `u.out.field` would still read the child's id.
    root.owner.foreach(_.registerAggregate(root.id, (newRoot, drivenBy) =>
      b.rebind(newRoot)
      drivenBy.foreach(b.markLeavesDrivenBy)))
    // ...and the same for the other direction. A Bundle port bound as a
    // submodule *input* keeps the child's signal, so there is nothing to
    // re-point — but the leaves must learn which instance and port they are,
    // or `u.in.field := expr` would not know it is a connection.
    root.owner.foreach(_.registerAggregateInput(root.id,
      (parent, index, portName) => b.markLeavesInstanceInput(parent, index, portName)))
    b

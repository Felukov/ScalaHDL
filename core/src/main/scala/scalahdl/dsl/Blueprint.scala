package scalahdl.dsl

import scalahdl.core.*
import SignalId.*

/** R31 — What an interface field may be declared with, and what its `val`
  * holds once the endpoint is declared.
  *
  * `P` is the blueprint written in the declaration and `H` the handle bound
  * to the port:
  *
  * {{{
  *   written                    held
  *   a Type      Out(UInt(8))   DslSignal
  *   a Bundle    Out(Word())    the bundle, its fields typed
  *   a Vec       Out(Vec(2, Word()))   BundleVecSignal[Word]
  *   an Encoding In(AluOp())    DslSignal, carrying the encoding
  * }}}
  *
  * A user's interface never names the pair: `Out(Word())` picks it by the
  * argument. A generic one — `Handshake`, `Flow` — carries it as
  * `(using Blueprint[P, H])`, which is what lets its `payload` come back as
  * the bundle when the payload is one and as a signal when it is not. Both
  * are inferred at the declaration. */
trait Blueprint[P, H]:
  /** The direction-free type of the port. */
  def typeOf(p: P): Type
  /** The handle the field `val` holds before the endpoint is declared — the
    * placeholder that the port is later declared onto. */
  def unbound(p: P, name: String): H
  /** Declare the port through `allocate` and make `h` its handle. Returns the
    * root signal. */
  def bind(p: P, h: H, allocate: Interface.Allocate): DslSignal

object Blueprint:

  private def placeholder(name: String, t: Type): DslSignal =
    new DslSignal(SignalId(-1), name, t, false, Nil)

  /** `Out(UInt(8))`, `In(Vec(4, Bool()))` — any `Type`, held as a signal. */
  given ofType[T <: Type]: Blueprint[T, DslSignal] with
    def typeOf(t: T): Type = t
    def unbound(t: T, name: String): DslSignal = placeholder(name, t)
    def bind(t: T, h: DslSignal, allocate: Interface.Allocate): DslSignal =
      allocate(t, None, Some(h))

  /** `Out(Word())` — a bundle, held as itself: the port is allocated and the
    * same instance attached, exactly as `Out(b)` at module level. */
  given ofBundle[B <: Bundle]: Blueprint[B, B] with
    def typeOf(b: B): Type = b.toType
    def unbound(b: B, name: String): B = b
    def bind(b: B, h: B, allocate: Interface.Allocate): DslSignal =
      val root = allocate(b.toType, None, None)
      Bundle.attach(root, b)
      root

  /** `Out(Vec(2, Word()))` — a Vec of bundles, held as its typed handle. */
  given ofBundleVec[B <: Bundle]: Blueprint[BundleVec[B], BundleVecSignal[B]] with
    def typeOf(v: BundleVec[B]): Type = v.toType
    def unbound(v: BundleVec[B], name: String): BundleVecSignal[B] =
      new BundleVecSignal(placeholder(name, v.toType), v)
    def bind(v: BundleVec[B], h: BundleVecSignal[B], allocate: Interface.Allocate): DslSignal =
      allocate(v.toType, None, Some(h.root))

  /** `In(AluOp())` — a named encoding (R24), held as a signal that carries it. */
  given ofEncoding[E <: Encoding]: Blueprint[E, DslSignal] with
    def typeOf(e: E): Type = e.toType
    def unbound(e: E, name: String): DslSignal = placeholder(name, e.toType)
    def bind(e: E, h: DslSignal, allocate: Interface.Allocate): DslSignal =
      allocate(e.toType, Some(e.info), Some(h))

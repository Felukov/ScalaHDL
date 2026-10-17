package scalahdl.dsl

import scalahdl.core.*

/** Convert a typed Vec-of-Bundle blueprint to its structural IR type. */
given [B <: Bundle]: Conversion[BundleVec[B], Type] = _.toType

/** R14 — scalar/structural Vec blueprint. */
inline def Vec(n: Int, elem: Type): Type = Type.Vec(n, elem)

/** A typed Vec-of-Bundle blueprint. It retains a factory so indexing a bound
  * Vec can return the concrete Bundle subtype rather than raw Bits or a
  * stringly field accessor. Construction eagerly evaluates one fresh factory
  * per element, as required by R14, and checks structural homogeneity. */
final class BundleVec[B <: Bundle] private[dsl] (
    val size: Int, private[dsl] val factory: () => B, val elemType: Type.Bundle,
    private[dsl] val elements: Vector[B]):
  def toType: Type.Vec = Type.Vec(size, elemType)

/** Typed handle for a bound Vec-of-Bundle. `apply` returns `B`, so
  * `entries(2).addr` is ordinary statically typed Scala field access.
  *
  * `root` is the Vec as one signal, and is internal for the reason
  * `Bundle.whole` is: the conversions below say the same thing without it. */
final class BundleVecSignal[B <: Bundle] (
    private[scalahdl] val root: DslSignal, blueprint: BundleVec[B]):
  def typeOf: Type = root.typeOf
  def apply(i: Int): B =
    require(i >= 0 && i < blueprint.size,
      s"Vec index $i is outside 0..${blueprint.size - 1}")
    val b = blueprint.factory()
    b.rebind(root(i))
    b
  def apply(i: DslSignal): B =
    val b = blueprint.factory()
    b.rebind(root(i))
    b

/** `Vec(n, bundleFactory)` — typed Vec-of-Bundle construction. */
def Vec[B <: Bundle](n: Int, factory: => B): BundleVec[B] =
  require(n > 0, "Vec(n, bundleFactory) requires n > 0")
  val make = () => factory
  val elements = Vector.fill(n)(make())
  val elemType = elements.head.toType
  elements.zipWithIndex.tail.foreach { case (element, index) =>
    require(element.toType == elemType,
      s"Vec element $index has type ${element.toType}, but element 0 has type $elemType")
  }
  new BundleVec(n, make, elemType, elements)

/** Curried spelling retained for the specification's `Vec(4)(Entry())`. */
@scala.annotation.targetName("bundleVecCurried")
def Vec[B <: Bundle](n: Int)(factory: => B): BundleVec[B] = Vec(n, factory)

/** R31 — `n` endpoints of one interface, declared together.
  *
  * **This is the one `Vec` that is not a signal**, and it cannot be: an
  * endpoint is a group of ports, not a value, so there is nothing to pack and
  * nothing to index at run time. What `Vec` means here is what it means
  * everywhere — *n of these, named `_0` to `_n-1`* — and the names it puts on
  * the pins are the ones R14.1 already gives a port `Vec`.
  *
  * Its use is a module that faces `n` of something: an arbiter, a crossbar, a
  * fan-out. Each element is an ordinary endpoint once bound, so `<>` connects
  * one of them exactly as it connects any other.
  *
  * A fresh interface per element, as R14 requires of a `Vec` of bundles and
  * for a sharper reason: an `Interface` may be bound once, so sharing one
  * instance across the elements is an error rather than aliasing. */
final class InterfaceVec[I <: Interface] private[dsl] (
    val size: Int, private[dsl] val elements: Vector[I])

/** `Vec(n, interfaceFactory)` — R31 endpoints, not a signal. See
  * [[InterfaceVec]]. */
@scala.annotation.targetName("interfaceVec")
def Vec[I <: Interface](n: Int, factory: => I): InterfaceVec[I] =
  require(n > 0, "Vec(n, interfaceFactory) requires n > 0")
  val elements = Vector.fill(n)(factory)
  val first = elements.head.shape
  elements.zipWithIndex.tail.foreach { case (element, index) =>
    require(element.shape == first,
      s"Vec element $index is a different shape from element 0")
  }
  new InterfaceVec(n, elements)

/** R07/R14 — a bound Vec-of-Bundle is its signal, and its packed bits, exactly
  * as a `Bundle` is. The two aggregate handles differ in what indexing them
  * returns, not in what reading them means, and a rule that held for one and
  * not the other would be the ragged half of R07.2. */
given [B <: Bundle]: Conversion[BundleVecSignal[B], DslSignal] = _.root

given [B <: Bundle]: Conversion[BundleVecSignal[B], Expr] = _.root.ref

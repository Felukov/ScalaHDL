package scalahdl.emit

import scalahdl.core.*

/** R07.1 — Deterministic flattening. A bundle field's Verilog name is the path
  * from the named root, joined by `_`. `Vec` in the path contributes a numeric
  * index. This is emission-time (R17: aggregates preserved in the IR; the
  * emitter resolves leaf names when writing).
  *
  * The two contexts where a `Vec` appears differ (R14.1):
  *
  *   - at a port: per-element scalars (`entries_0_addr`) — Verilog-2001 forbids
  *     array ports, so a port `Vec` expands to one name per element.
  *   - as an internal signal: struct-of-arrays (`entries_addr`) — one net array
  *     per leaf field, indexed by a runtime index. `LeafName` produces the
  *     *per-leaf-array* base name; the emitter appends `[idx]`.
  *
  * This module is shared by the emitter (port/declaration expansion and
  * expression/lhs rendering) and by `NamePhase` (leaf-name collision check). */
object LeafName:

  /** Expand an aggregate root into its leaf `(name, type)` pairs.
    *
    *   - a scalar root → one leaf: `(rootName, rootType)`.
    *   - a `Bundle` root → one leaf per field, joined: `root_field`, recursing
    *     into nested bundles (`root_inner_field`).
    *   - a `Vec` root → per-element (port form): `root_0_field`, …, `root_{n-1}_field`.
    *     The caller chooses per-element vs struct-of-arrays by context; this
    *     method yields the per-element expansion. `internalLeafArrays` yields
    *     the struct-of-arrays form for internal signals.
    *
    * The join is `_`-separated; `Vec` contributes the numeric index. */
  def leaves(rootName: String, rootType: Type): List[(String, Type)] =
    rootType match
      case Type.Bundle(fields) =>
        fields.flatMap { case (fn, ft) => leaves(s"${rootName}_$fn", ft) }
      case Type.Vec(n, elem) =>
        // Port form: per-element. `Vec` contributes the index.
        (0 until n).toList.flatMap { i => leaves(s"${rootName}_$i", elem) }
      case scalar =>
        List((rootName, scalar))

  /** One scalar leaf in the Verilog-2001 internal struct-of-arrays lowering.
    * `dimensions` contains every enclosing Vec length, outermost first. */
  final case class InternalLeaf(name: String, scalarType: Type, dimensions: List[Int])

  /** Recursively lower an internal aggregate to scalar leaves while preserving
    * every Vec dimension (strict R14 arbitrary nesting). Bundles contribute
    * name segments; Vecs contribute unpacked array dimensions. For example,
    * `Vec(2, Bundle(xs = Vec(4, UInt(8))))` becomes
    * `InternalLeaf("root_xs", UInt(8), List(2, 4))`. */
  def internalLeaves(rootName: String, rootType: Type): List[InternalLeaf] =
    def loop(name: String, t: Type, dims: List[Int]): List[InternalLeaf] = t match
      case Type.Bundle(fields) =>
        fields.flatMap { case (fieldName, fieldType) =>
          loop(s"${name}_$fieldName", fieldType, dims)
        }
      case Type.Vec(n, elem) =>
        loop(name, elem, dims :+ n)
      case scalar =>
        List(InternalLeaf(name, scalar, dims))
    loop(rootName, rootType, Nil)

  /** Compatibility view retained for shallow callers. New code must use
    * `internalLeaves` so nested Vec dimensions cannot be discarded. */
  def internalLeafArrays(rootName: String, rootType: Type): List[(String, Type, Int)] =
    internalLeaves(rootName, rootType).map(l => (l.name, l.scalarType, l.dimensions.length))

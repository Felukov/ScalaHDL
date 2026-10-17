package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, param}
import scalahdl.clock.ClockDomain

/** Leaf in a three-level hierarchy used to demonstrate deterministic emission.
  * The parameter contributes to the emitted module name, so the two leaf
  * declarations below remain distinct files. */
class NestedLeaf(index: Int) extends Module(param("i", index))

/** Middle level. Child instances are declared left-to-right, which is also the
  * tie-breaking order used by depth-first emission. */
class NestedBranch(index: Int) extends Module(param("i", index)):
  given ClockDomain = ClockDomain.noClock

  val firstLeaf = inst(new NestedLeaf(index * 2))
  val secondLeaf = inst(new NestedLeaf(index * 2 + 1))

/** No-argument top suitable for `scalahdl.examples.Emit`.
  *
  * The resulting manifest demonstrates DFS post-order:
  *
  *   NestedLeaf_i0.v
  *   NestedLeaf_i1.v
  *   NestedBranch_i0.v
  *   NestedLeaf_i2.v
  *   NestedLeaf_i3.v
  *   NestedBranch_i1.v
  *   NestedModules.v
  */
class NestedModules extends Module:
  given ClockDomain = ClockDomain.noClock

  val leftBranch = inst(new NestedBranch(0))
  val rightBranch = inst(new NestedBranch(1))

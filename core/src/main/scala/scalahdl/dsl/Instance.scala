package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module
import scalahdl.clock.ClockDomain

/** R13.2 — Module instantiation. `inst(new Child)` creates the child module,
  * records it as an `Instance` in the enclosing module's IR, and
  * auto-connects the child's clock/reset ports from the enclosing domain.
  *
  * Explicit port overrides (`u.clk := someClk`) are recorded after the
  * instance is created and take precedence over the auto-connection at freeze
  * time.
  *
  * The instance name is the `val` name (R1), captured via `Source.capture`.
  * `Source.capture` is passed as an inline parameter so the R1 macro's
  * `Symbol.spliceOwner` resolves to the user's binding `val` (e.g. `val u`),
  * not an intermediate inside `inst`. */
inline def inst[T <: scalahdl.elab.Module](child: => T)(
    using m: Module, cd: ClockDomain): T =
  addInst(child, Source.capture)

/** R16/R23 — an *array* of instances, one per index, where the count is a
  * value rather than a number of `val`s:
  *
  * ```scala
  * val slot = instances(map.length)(i => new Scratchpad(map(i).width))
  * slot(0).req := requests(0)
  * ```
  *
  * They are named `slot_0` … `slot_{n-1}`: the name still derives from the
  * binding `val` (R1), and the index is the same numeric segment R07.1/R14.1
  * already append when flattening a `Vec`, so an instance array and a port
  * `Vec` read alike in the output. Nothing here is a global counter — adding
  * an instance array in one module renumbers nothing in another, which is the
  * property R16 rejects a counter suffix for.
  *
  * Each child's parent-side output nets follow from its instance name as
  * usual, so element `i` of `slot` drives `slot_i_<port>` (R2/R23).
  *
  * The factory takes the index so an array may be heterogeneous in its
  * *parameters* — `i => new Scratchpad(map(i).width)` — while remaining one
  * declaration. Distinct parameters simply emit distinct modules (R16). */
inline def instances[T <: scalahdl.elab.Module](n: Int)(child: Int => T)(
    using m: Module, cd: ClockDomain): IndexedSeq[T] =
  addInstances(n, child, Source.capture)

private def addInstances[T <: scalahdl.elab.Module](n: Int, child: Int => T, src: Source)(
    using m: Module, cd: ClockDomain): IndexedSeq[T] =
  require(n > 0, s"instances(n) requires n > 0, got $n")
  // An anonymous array has no name to index, and `addInst` already raises R2's
  // error for that; mapping the option keeps that one message.
  Vector.tabulate(n) { i =>
    addInst(child(i), Source(src.name.map(base => s"${base}_$i"), src.loc))
  }

private def addInst[T <: scalahdl.elab.Module](child: => T, src: Source)(
    using m: Module, cd: ClockDomain): T =
  val elaboratedChild = child
  elaboratedChild match
    case blackBox: scalahdl.elab.BlackBox =>
      m.builder.addBlackBox(blackBox, m.builder.activeDomain(cd), src)
    case generated =>
      m.builder.addInstance(generated, generated.builder.portsList,
        m.builder.activeDomain(cd), src)
  elaboratedChild

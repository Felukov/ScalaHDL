package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module

/** R14.3 — Decoder helper. `decode(index, size)` returns a one-hot `Vec[Bool]`
  * of `size` elements: element `j` is high when `index === j`. Combinational
  * dynamic-index writes are expressed as explicit per-element assignments
  * guarded by it (see the spec idiom).
  *
  * The library provides **no** implicit write-decoding combinator — by design.
  * A one-hot vector composes better: it can drive enables, read muxes, or
  * valid bits, not only writes. A grep test confirms the absence of a
  * `writeDecoded` helper. */
def decode(index: DslSignal, size: Int)(using m: Module, cd: ClockDomain): DslSignal =
  val sel = Wire(Vec(size, Bool()))
  for j <- 0 until size do
    // `Assign.at(…, NoLoc)` rather than `:=`: `:=` captures its call site for
    // R19, and this one is inside the library, which is not where a caller's
    // mistake would be.
    Assign.at(sel(j), index === j.U, Loc.NoLoc)
  sel

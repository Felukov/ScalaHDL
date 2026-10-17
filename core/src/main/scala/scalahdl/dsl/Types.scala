package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module
import scalahdl.clock.ClockDomain
import scalahdl.core.ResetPolarity

/** R6 — Surface signal types. `Bool()`, `UInt(w)`, `SInt(w)`, `Bits(w)` are
  * the hardware *type* constructors — they produce a `Type`, not a signal.
  * `In`/`Out`/`Reg` take a `Type` and produce a `DslSignal` (a named signal
  * recorded into the builder). This matches the spec: `In(UInt(8))`,
  * `Reg(UInt(8), init = 0)`.
  *
  * Widths are plain `Int` values (R6): `UInt(8)`. Width *checking* is the
  * `WidthPhase` at validation; this file only carries the width.
  *
  * R13.1 — `Clock()` and `Reset(polarity)` are distinct from `Bool` at the
  * Scala type level so a clock cannot be used as data and a domain can only
  * be built from correctly-typed signals. */

/** `Bool()` — the single-bit hardware type. */
def Bool(): Type = Type.Bool

/** `UInt(w)` — the unsigned integer type of width `w`. */
def UInt(w: Int): Type = Type.UInt(w)

/** `SInt(w)` — the signed integer type of width `w`. */
def SInt(w: Int): Type = Type.SInt(w)

/** `Bits(w)` — the raw bit-vector type of width `w`; no arithmetic (R6). */
def Bits(w: Int): Type = Type.Bits(w)

/** `Clock()` — the clock type. Distinct from `Bool` (R13.1): a clock cannot
  * be used as data. Verilog emits it as a plain 1-bit `input` wire. */
def Clock(): Type = Type.Clock

/** `Reset(polarity)` — the reset type. Distinct from `Bool` (R13.1): a reset
  * cannot be used as data, and a domain can only be built from a correctly
  * typed reset signal. Defaults to active-low (the common convention for
  * asynchronous resets). */
def Reset(polarity: ResetPolarity = ResetPolarity.ActiveLow): Type =
  Type.Reset(polarity)

package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module

/** R22 — First-class switch/case control flow.
  *
  * ```scala
  * switch(state) {
  *   is(stateReset)   { state := stateRequest }
  *   is(stateRequest) { state := stateWait }
  *   default          { state := stateReset }
  * }
  * ```
  *
  * The body is an *elaboration scope*, not a data structure: `is` and `default`
  * are calls that enlist an alternative in the switch currently open on the
  * builder. That is what makes the construct generator-friendly — a `for`, a
  * `foreach`, an iterator, or a helper method may call `is` while the scope is
  * open, and the result is identical to writing those calls out by hand.
  *
  * ```scala
  * switch(opcode) {
  *   for entry <- decodeTable do is(entry.opcode) { result := entry.value }
  *   default { illegal := true }
  * }
  * ```
  *
  * The producer is ordinary Scala and never enters the IR (R2); only the
  * alternatives it produced do, in call order (R20.1: the same program emits
  * the same bytes). Because the scope is dynamic rather than lexical, `is`
  * outside an open `switch` is a diagnosable error rather than a type error —
  * the cost of admitting helpers, and R19's job to make legible.
  *
  * The three entry points are `inline` so each captures its own call site
  * (`Loc.here`): a duplicate case value is reported at the later `is` and
  * related to the first, and neither is a declaration any other capture would
  * find. */

/** `switch(selector) { ... }` — open a multi-way scope over one bit-typed
  * selector. Alternatives are collected as the body elaborates and the whole
  * switch is recorded, per written signal, when the body finishes. */
inline def switch(inline selector: Expr)(inline body: => Unit)(using m: Module): Unit =
  m.builder.pushSwitch(selector, Loc.here)
  body
  m.builder.popSwitch()

/** `is(v1, v2, ...) { ... }` — one alternative, selected by any of the listed
  * elaboration-time constant values, all sharing one body. Values keep their
  * order, so `is(a, b)` emits two labels on one case item in that order. */
inline def is(values: Expr*)(inline body: => Unit)(using m: Module): Unit =
  m.builder.beginCase(values.toList, Loc.here)
  body
  m.builder.endCase()

/** `default { ... }` — the alternative for every value no `is` names. Optional,
  * and at most one per switch; its presence is what makes a switch exhaustive
  * without covering the selector's whole value space (R11). */
inline def default(inline body: => Unit)(using m: Module): Unit =
  m.builder.beginDefault(Loc.here)
  body
  m.builder.endDefault()

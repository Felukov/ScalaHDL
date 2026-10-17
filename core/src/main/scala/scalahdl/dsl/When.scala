package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module

/** R12 — Conditional assignment. `when`/`elsewhen`/`otherwise` push condition
  * scopes on the builder; `:=` records assignments at the current scope.
  *
  * Nesting depth, branch order, and statement order in the emitted Verilog
  * match the source — no ternary collapse, no reordering, no merging of
  * separate blocks (R12). The builder collects all assignments to one signal
  * into a single driver block in source order.
  *
  * The `WhenBlock` returned by `when` carries `elsewhen`/`otherwise` so the
  * else chain reads naturally at the call site. It also carries the *identity*
  * of the chain the builder just closed: an else arm reopens that chain rather
  * than recording into the enclosing scope, which is what makes
  * `when(c) { y := a }.otherwise { y := b }` an `if`/`else` instead of an `if`
  * followed by an unconditional assignment. A `WhenBlock` kept in a `val` and
  * used after other statements can no longer reopen, and says so. */

/** A handle to a just-closed `when`/`elsewhen` chain, for attaching the else
  * branch. `id` names the chain the builder may reopen.
  *
  * R19 — each arm records `Loc.here`, the position of the call being inlined,
  * which for `when` is the `when` itself.
  *
  * **A chained `elsewhen`/`otherwise` records the `when` at the head of its
  * chain, not its own line.** A chain is one expression and `elsewhen` is a
  * call on its receiver, so the position of the expansion is the receiver's.
  * The condition's own tree would be the better answer and is not reachable:
  * an `inline` parameter does not carry a usable position into a nested macro
  * (`Position.ofMacroExpansion` gives the receiver, and the parameter's term
  * carries a position belonging to some other tree entirely). A width error in
  * an `elsewhen` condition therefore names the conditional it belongs to rather
  * than the arm; the `note:` lines still name both signals. */
final class WhenBlock(m: Module, id: Int):
  /** `elsewhen(cond) { ... }` — an else-if: a new condition nested inside the
    * previous frame's else arm. */
  inline def elsewhen(inline cond: Expr)(inline body: => Unit): WhenBlock =
    m.builder.beginElseWhen(id, cond, scalahdl.core.Loc.here)
    body
    new WhenBlock(m, m.builder.endWhen())

  /** `otherwise { ... }` — the final else. */
  inline def otherwise(inline body: => Unit): Unit =
    m.builder.beginOtherwise(id)
    body
    val _ = m.builder.endWhen()

/** `when(cond) { ... }` — open a conditional assignment scope. */
inline def when(inline cond: Expr)(inline body: => Unit)(using m: Module): WhenBlock =
  m.builder.beginWhen(cond, scalahdl.core.Loc.here)
  body
  new WhenBlock(m, m.builder.endWhen())

package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module

/** R26 — Selection on conditions.
  *
  * R22 partitions one selector by constant labels. Some partitions have no
  * constants to label with: a decoder's alternatives are *ternary matches*
  * over an opcode word, and there are eight hundred of them.
  *
  * ```scala
  * parallelCase {
  *   for cube <- cubes do
  *     on((opcode & cube.mask.U(16)) === cube.value.U(16)) { shape := cube.shape.U(9) }
  *   default { shape := 0.U(9) }
  * }
  * ```
  *
  * Both forms emit Verilog's `case (1'b1)` — the selector is the constant one
  * and each arm's label is its own condition — and both are ordinary R22
  * switches in the IR, so R12's projection, R22.1's pruning and R11's coverage
  * apply without knowing which kind they are reading.
  *
  * The two differ in what the source *claims*. `priorityCase` claims nothing:
  * the first arm that holds wins, which is what the `when` chain it replaces
  * already meant. `parallelCase` claims at most one arm can hold — and R26
  * decides that claim at elaboration rather than passing it to the synthesiser
  * as an attribute, which is why its arms must be cubes.
  */

/** `priorityCase { on(c) { ... } ... }` — the first arm whose condition holds.
  * Any one-bit condition is admissible, because nothing is claimed about it. */
inline def priorityCase(inline body: => Unit)(using m: Module): Unit =
  m.builder.pushSelect(SelectKind.Priority, Loc.here)
  body
  m.builder.popSwitch()

/** `parallelCase { on(c) { ... } ... }` — the same structure, with the added
  * claim that at most one condition can hold. Every arm must be a masked
  * compare against one common selector, and R26 reports an overlapping pair
  * rather than trusting the claim. */
inline def parallelCase(inline body: => Unit)(using m: Module): Unit =
  m.builder.pushSelect(SelectKind.OneHot, Loc.here)
  body
  m.builder.popSwitch()

/** `on(c1, c2, ...) { ... }` — one alternative, taken when any of the listed
  * one-bit conditions holds. The sibling of R22's `is`, and separate from it
  * because `is` names a value and this names a condition: reusing the word
  * would make `is(x)` mean "equals x" in one construct and "x is true" in the
  * other. */
inline def on(conditions: Expr*)(inline body: => Unit)(using m: Module): Unit =
  m.builder.beginSelectArm(conditions.toList, Loc.here)
  body
  m.builder.endCase()

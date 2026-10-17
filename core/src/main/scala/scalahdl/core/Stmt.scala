package scalahdl.core

import SignalId.*

/** R17 — Statement. Assignment (blocking or non-blocking), conditional with
  * then/else statement lists, multi-way selection, and an immediate assertion.
  * A memory write is not a driver statement: its ports live on `Memory`
  * (`MemoryWritePort`), and `MemWrite` carries one only when a statement has
  * to name a write target.
  *
  * Nesting and order are preserved exactly (R12): a `When` carries its then
  * and else lists in source order, and assignment order within a list is the
  * order the user wrote. The emitter walks these lists verbatim; it does not
  * reorder, merge, or collapse into ternaries.
  *
  * R14: `Assign.target` is an `Lhs` so sub-element writes — `vec(i) := x`
  * (R14.2), `bundle.field := x`, and R6.7's `count(7, 0) := d` — are
  * first-class. An aggregate signal has
  * one `DriverBlock` (R12: one signal, one block) whose statements carry
  * `Lhs`-targeted assigns naming leaves or whole-array elements. */
enum Stmt:
  case Assign(target: Lhs, expr: Expr, blocking: Boolean, loc: Loc)
  case When(cond: Expr, thenStmts: List[Stmt], elseStmts: List[Stmt], loc: Loc)
  /** R22 — Multi-way control flow. A first-class statement, never lowered to
    * nested `When`s: the source wrote one partition of one selector, and the
    * IR says so, which is what lets the backend emit a native Verilog `case`
    * and lets R11 reason about coverage of the selector's value space rather
    * than about a chain of independent conditions.
    *
    * `cases` is in elaboration order, which is source order for handwritten
    * arms and call order for arms an ordinary Scala producer enlisted. An
    * absent `default` (`None`) and a present but empty one (`Some(Nil)`) are
    * different statements: the first leaves the unnamed values uncovered, the
    * second covers them with no assignment. R11 reaches the same verdict for
    * both, but only because it asks whether the arm *assigns the target*, not
    * whether the arm exists.
    *
    * R26 — `kind` says how the alternatives select. `Values` is R22's: the
    * labels are constants compared against the selector. `Priority` and
    * `OneHot` carry the constant one as the selector and a *condition* per
    * label, which Verilog spells `case (1'b1)`. It is deliberately the same
    * statement: R12's projection, R22.1's pruning and R11's coverage read the
    * alternatives without caring what selected them, and duplicating the node
    * would duplicate all three. */
  case Switch(kind: SelectKind, selector: Expr, cases: List[SwitchCase],
              defaultStmts: Option[List[Stmt]], loc: Loc)
  case MemWrite(id: SignalId, addr: Expr, data: Expr, loc: Loc)
  /** R27 — An immediate assertion: a claim about the design that is checked in
    * simulation and is not part of the circuit.
    *
    * `block` is the `CheckBlock` this statement was filed under, exactly as
    * `MemWrite` carries the memory it writes. A statement in this IR is filed
    * by *what it targets*, because that is what R12's projection sorts on: a
    * `when` wrapped round two assignments and an assertion is copied into
    * three blocks with the other two statements removed. An assertion targets
    * no signal, so it is given a block of its own to be filed under, and the
    * projection machinery needs to know nothing else about it.
    *
    * `message` is the text a failure prints. It is a plain `String` fixed at
    * elaboration and not a format with arguments: the value of every signal the
    * condition names is visible in the waveform at the time the check fires,
    * and a formatted report is what R27 defers to `display`. */
  case Check(block: SignalId, cond: Expr, message: String, loc: Loc)

/** R22/R26 — How a `Switch`'s alternatives select.
  *
  * `Values` compares constant labels against a selector. `Priority` and
  * `OneHot` evaluate one-bit conditions in order and take the first that
  * holds; they differ only in what the source *claimed* — `OneHot` says at
  * most one can hold, and R26 decides that claim at elaboration.
  *
  * R26.1 — and the decided claim then travels: an `OneHot` case is emitted
  * with `parallel_case`, because a synthesiser cannot see the proof and must
  * otherwise build the priority chain Verilog's `case` defines. What makes
  * that sound is R26's refusal to accept an arm it cannot decide — a design
  * the attribute would miscompile does not elaborate.
  */
enum SelectKind:
  case Values, Priority, OneHot

  /** True where the labels are conditions rather than constants. */
  def onConditions: Boolean = this != SelectKind.Values

/** R22 — One alternative of a `Switch`: the values that select it, the
  * statements it runs, and the `is(...)` call's source location.
  *
  * `values` is non-empty (the DSL rejects `is()` at the call) and ordered, so
  * `is(a, b)` emits one body under two comma-separated labels in that order.
  * Every value is an elaboration-time constant of the selector's exact kind and
  * width after R6.1/R21.7 resolution; `SwitchPhase` is what enforces that, and
  * `loc` is what its duplicate diagnostic points at. */
final case class SwitchCase(values: List[Expr], stmts: List[Stmt], loc: Loc)

/** R17 — Assignment target. A whole signal (`Sig`) or a sub-element of an
  * aggregate (`Field`/`Index`). The root is always a `SignalId`; structure is
  * carried recursively so the emitter can resolve the leaf name (R07.1) or the
  * net-array index (R14.1) at write time. This is what lets `vec(i) := x` name
  * a runtime-indexed element without flattening the aggregate during
  * elaboration (R17: aggregates preserved). */
enum Lhs:
  case Sig(id: SignalId)
  case Field(base: Lhs, name: String)
  case Index(base: Lhs, idx: Expr)
  /** R6.7 — a contiguous window of a bit-vector target: `count(7, 0) := d`.
    *
    * The mirror of `Expr.Slice` on the writing side, and the reason it is a
    * separate case rather than a repeated `Index` is that Verilog spells it as
    * one part-select and the width it accepts is the window's, not one bit.
    * The window is constant — a variable part-select is Verilog-2001's `+:`
    * indexed form and a different construct — and it is the last step of a
    * path, because Verilog has no selection *inside* a part-select. */
  case Slice(base: Lhs, hi: Int, lo: Int)
  /** R34 — a window with a runtime low bit: `target[idx +: width] := d`. The
    * write-side mirror of `Expr.VarSlice`, and the last step of a path for the
    * same reason `Slice` is. */
  case VarSlice(base: Lhs, lo: Expr, width: Int)

  /** The root signal id — the aggregate that owns this sub-element. A
    * `DriverBlock` is keyed by this id. */
  def rootId: SignalId = this match
    case Sig(id)            => id
    case Field(base, _)     => base.rootId
    case Index(base, _)     => base.rootId
    case Slice(base, _, _)  => base.rootId
    case VarSlice(base, _, _) => base.rootId

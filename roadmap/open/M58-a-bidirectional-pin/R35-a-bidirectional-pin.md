# R35 — A bidirectional pin

**Milestone:** M58 — A bidirectional pin
**Status:** open
**Spec:** §6 Ports and logic, R10 (module ports); §5 Types, R6 (an expression
literal); §8 Blackboxes, R18 (blackbox ports). **A new spec rule, R35, is
required** if anything is adopted — see [Spec follow-up](#spec-follow-up)
**Depends on:** R10 (the direction enum), R6/Expr (the literal IR), R11 and R28
(the driven-everywhere and settle checks a tri-state signal complicates), R18
(a blackbox port's direction)
**Unblocks:** bidirectional pads, shared/tri-state buses, and vendor IP with an
`inout` pin

## Goal

**Decide whether ScalaHDL can express a bidirectional pin — a port with
direction `inout` and a high-impedance (`z`) output state — or whether it stays
strictly unidirectional and documents split input/output plus external tri-state
as the only supported idiom.** If the first is chosen, build it; if the second,
say so and state the gap it leaves.

The task records a missing capability and its options. An outcome of "stay
unidirectional, for this reason" satisfies the task; an outcome of "adopt
`InOut` and `z`" obliges the build and the spec text that goes with it.

## Why now

A completeness analysis of the synthesisable Verilog-2001 subset found ScalaHDL
can express nearly everything, with **exactly two** load-bearing blockers for
which no equivalent encoding exists; this is the second (the first is
[R34, a runtime selection on a packed vector](../../../hdl-spec.md#a-runtime-selection-on-a-packed-bit-vector-r34)). Nothing in
the library can produce a tri-state pin:

- `Direction` has only `In` and `Out` —
  [`Direction.scala:4`](../../../core/src/main/scala/scalahdl/core/Direction.scala).
- No high-impedance literal exists in the expression IR; `Expr.Lit` is a value
  with a width and a kind —
  [`Expr.scala:31`](../../../core/src/main/scala/scalahdl/core/Expr.scala).
- The emitter never writes `z` —
  [`VerilogEmitter.scala:849`](../../../core/src/main/scala/scalahdl/emit/VerilogEmitter.scala).
- A blackbox cannot restore the direction: R18 types its ports with the same
  enum, and there is no `InOut` for it to declare.

```
  Direction:  In | Out           (no InOut)
  Expr.Lit :  value · width · kind  (no z)
  emitter  :  input | output     (never "inout", never a z assignment)
```

**The partial mitigation, and its limit.** The analysis recorded the common
on-chip alternative as a real but **partial** mitigation: declare a split
input/output pair — one `Out` for the driven value, one `In` for the sampled
value — and place the tri-state pad, or the external `inout` pin's own buffer,
outside the emitted design. It removes the need for `z` on many parts, and an
internal shared bus can be replaced by a mux; but it cannot describe a vendor
module whose port list itself says `inout`, and it moves the tri-state out of
the design rather than expressing it. The decision below must weigh this against
adding the construct — the analysis did not decide it, and neither does this
task.

## Design framing

The framing below is what a future decision on this task needs. Nothing in it is
decided.

### The port model

A direction is one enum, `In | Out` plus a speller for Verilog —
[`Direction.scala:4`](../../../core/src/main/scala/scalahdl/core/Direction.scala).
A port is separate from the signal it names: `Port(name, direction, typeOf,
loc)` ([`Port.scala:8`](../../../core/src/main/scala/scalahdl/core/Port.scala)),
and `addPort` also declares a backing signal
([`Builder.scala:435`](../../../core/src/main/scala/scalahdl/elab/Builder.scala),
[:440](../../../core/src/main/scala/scalahdl/elab/Builder.scala)). The DSL spells
only the two directions
([`Ports.scala:26`](../../../core/src/main/scala/scalahdl/dsl/Ports.scala),
[:59](../../../core/src/main/scala/scalahdl/dsl/Ports.scala)). What reaches the
emitted keyword is one line, `${p.direction.verilog}`
([`VerilogEmitter.scala:71`](../../../core/src/main/scala/scalahdl/emit/VerilogEmitter.scala)):
an added direction would reach the port list through that speller, not through
the DSL.

### One enum, matched with no default

`Direction` is matched with **no default case** in four places, so an added case
either fails to compile or forces a decision at each:

- `Direction.verilog`
  ([`Direction.scala:7`](../../../core/src/main/scala/scalahdl/core/Direction.scala));
- `Interface.Role.direction`
  ([`Interface.scala:152`](../../../core/src/main/scala/scalahdl/dsl/Interface.scala));
- `Interface.side`
  ([`Interface.scala:304`](../../../core/src/main/scala/scalahdl/dsl/Interface.scala));
- `VerilogEmitter.isReg`
  ([`VerilogEmitter.scala:72`](../../../core/src/main/scala/scalahdl/emit/VerilogEmitter.scala)).

Beyond the matches, `Builder` filters signals by direction equality
([:485](../../../core/src/main/scala/scalahdl/elab/Builder.scala),
[:510](../../../core/src/main/scala/scalahdl/elab/Builder.scala),
[:529](../../../core/src/main/scala/scalahdl/elab/Builder.scala),
[:566](../../../core/src/main/scala/scalahdl/elab/Builder.scala),
[:628](../../../core/src/main/scala/scalahdl/elab/Builder.scala)), and `CombGraph`
filters the same way
([:428](../../../core/src/main/scala/scalahdl/core/CombGraph.scala),
[:437](../../../core/src/main/scala/scalahdl/core/CombGraph.scala)).

### The single-driver wall

`DriverBlock` is the one block that drives a signal
([`DriverBlock.scala:6`](../../../core/src/main/scala/scalahdl/core/DriverBlock.scala)),
and R11 makes an undriven combinational path an elaboration error
([`hdl-spec.md:1824`](../../../hdl-spec.md)), with registers exempt. There is
no representation of a second driver, and a tri-state net is deliberately
undriven by the module that declares it — the model cannot say that today. The
code does not settle how such a net would be represented.

### `z` has no kind

`Expr.Lit(value, width, kind)`
([`Expr.scala:31`](../../../core/src/main/scala/scalahdl/core/Expr.scala)) and
`LitKind` is `Unsigned | Signed | Bits | Bool`
([`Expr.scala:80`](../../../core/src/main/scala/scalahdl/core/Expr.scala)) — all
bit values. `z` is a high-impedance state, not a 0 or a 1, so it is none of
them. `ExprType` is total over the IR
([`ExprType.scala:15`](../../../core/src/main/scala/scalahdl/core/ExprType.scala))
and a literal takes its type through its kind
([:75](../../../core/src/main/scala/scalahdl/core/ExprType.scala),
[:96](../../../core/src/main/scala/scalahdl/core/ExprType.scala)). R6.1 and R6.4
assign widths only to the four kinds
([`hdl-spec.md:810`](../../../hdl-spec.md),
[:869](../../../hdl-spec.md)); the spec's treatment of the analogous one-bit
constant is that `True`/`False` "are not a type, not an IR node, and not an
emission rule" ([`hdl-spec.md:933`](../../../hdl-spec.md)). The emitter's literal
writer has no `z` arm
([`VerilogEmitter.scala:878`](../../../core/src/main/scala/scalahdl/emit/VerilogEmitter.scala)).

A new `Expr` node or kind is matched over across the library. At least these
files walk `Expr` and would each need a decision: `ExprType`, `ExprConst`,
`Leaf`, `AggregateRead`, `CombGraph`, `Cdc`, `ContinuousDriver`, `Builder`,
`VerilogEmitter`, `WidthPhase`, `CdcPhase`, `NeverRunsPhase`, `VecWritePhase`,
`CompletenessPhase`, `SwitchPhase`. This is the same family a new expression
node must be threaded through.

### The blackbox surface

`BlackBox` types its ports with the same enum
([`BlackBox.scala:135`](../../../core/src/main/scala/scalahdl/elab/BlackBox.scala)),
and several `require`s assume the two-case enum
([:41](../../../core/src/main/scala/scalahdl/elab/BlackBox.scala),
[:71](../../../core/src/main/scala/scalahdl/elab/BlackBox.scala),
[:120](../../../core/src/main/scala/scalahdl/elab/BlackBox.scala)).

### The principle tension

The three principles and the closing sentence are at
[`hdl-spec.md:23`](../../../hdl-spec.md): correspondence, locality,
explicitness, and "Where convenience conflicts with these, the properties win."
A tri-state net's meaning depends on drivers outside the module that declares
the pin, which bears on locality; and a deliberately undriven path meets R11
([`hdl-spec.md:1824`](../../../hdl-spec.md)), which bears on explicitness. The
tension is recorded here, not resolved.

### Size

Adding `InOut` changes an enum matched with **no default in four places** above,
where an expression node is added in one; and an `InOut` port additionally meets
the single-driver rule, which an expression node never has to. On both counts
this task is larger than a new expression surface.

## The four decisions, as choices

Each is a choice with its cost; none is resolved here.

1. **Add `InOut` and `z`, or keep the DSL unidirectional?**
   - *Add them*: a bidirectional pad and a tri-state bus become expressible, and
     a blackbox can declare a vendor `inout` pin. The cost is a construct whose
     meaning depends on the rest of the net.
   - *Stay unidirectional*: split input/output plus external tri-state is the
     only idiom, and a vendor `inout` pin is out of scope. The cost is the
     expressiveness gap.
2. **If added, is `z` an expression literal or an explicit construct (for
   example an enable on the port)?**
   - *A literal*, `z` beside the four `LitKind`s: it sits in the expression IR,
     and every walker above needs a decision; validation must decide where such a
     literal is legal.
   - *A construct*, for example an enable on the port: the high-impedance state
     is stated where the pin is driven, and validation has one place to check;
     the cost is a second mechanism beside the literal IR.
3. **How does an `inout` port meet the single-driver discipline, given that a
   port has no clock domain?** The single-driver rule assumes one driver. The
   decision must say whether such a net is exempt from R11, how an accidental
   omission is told from a deliberate float, and whether the net's drivers are
   named together.
4. **Does a blackbox gain `inout` in the same change or in a separate one?** R18
   types its ports with the same enum and its `require`s assume two cases; the
   decision is whether the module port and the blackbox port take the direction
   in one change or in two.

**A design note, recorded as an observation and not a decision:** the construct
option in decision 2 makes decision 3 easier, because the enable states when the
pin drives and when it floats.

## Scope

- [ ] **Decide** between adding `InOut` and a `z` literal, and staying
      unidirectional with split-bus documented (Open question 1); record the
      reason.
- [ ] If `InOut` is added, **decide how it sits with explicitness** — whether a
      tri-state net's non-local resolution is acceptable and whether the drivers
      of such a net must be named together (Open question 2).
- [ ] **Decide the emitter form and its legal contexts**, and restate R11/R28
      for a signal that may be undriven by design (Open question 3).
- [ ] **Decide whether a blackbox may declare `InOut`** (Open question 4).
- [ ] If adopted, implement the direction and the `z` form, emit them, and hold
      the emitted text by a golden.
- [ ] Write the R35 spec text, or record the decision to change nothing, with
      split-bus stated as the supported idiom.

## Non-goals

- Strength modelling beyond `0`/`1`/`z`. This is about whether `inout` and `z`
  are *sayable*, not about pull-ups or drive strengths.
- Per-field direction on a `Bundle`. R7 stays direction-free.
- The ergonomic shortcuts and deliberate exclusions already settled in the spec.
  This task adds none of them.

## Open questions

To be decided by the owner. Each is stated with its trade-off and **not**
pre-empted.

1. **Add `InOut` and a `z` literal, or stay unidirectional?**
   - *Add them*: express a tri-state bus and a bidirectional pad, and let a
     blackbox declare a vendor `inout` pin. The cost is a construct whose meaning
     depends on the rest of the net.
   - *Stay unidirectional*: document **split input/output plus external
     tri-state** as the only idiom and record that a vendor `inout` pin is out of
     scope. The cost is the expressiveness gap.
2. **How does an added `InOut` sit with the explicitness principle?** The
   `z` literal is asked for explicitly, so it fits the letter of explicitness.
   But a tri-state net's resolved behaviour depends on other drivers, which may
   be outside the declaring module, so the design's meaning is no longer local to
   one module. The decision must say whether that is acceptable and whether the
   library should require a tri-state net's drivers to be named together, so the
   meaning stays readable in one place.
3. **What does the emitter write, and where is it legal?**
   `assign pad = oe ? value : N'bz;` on an `inout` port, `z` only where a tool
   accepts it. Which of R11 (driven everywhere) and R28 (settles) must be
   restated for a signal a design deliberately leaves undriven for part of the
   time, and how is an accidental omission distinguished from a tri-state.
4. **May a blackbox declare `InOut`?** R18 exists so vendor IP need not be
   rewritten; an `inout` pin is ordinary vendor IP. If the direction is added,
   does a blackbox port take it, and does R18's domain declaration have anything
   to say about a pin that is both read and driven?

## Impact on the stated principles

The three non-negotiables are in [`hdl-spec.md`](../../../hdl-spec.md).

- **Correspondence.** Favourable if adopted: `inout` and `z` are the words a
  reader of the source expects, and the emitter writes them directly.
- **Locality.** Neutral at the surface; the tension is at *resolution*, not at
  renaming. A tri-state net's behaviour is not local to one module, which is
  what Open question 2 must settle.
- **Explicitness.** The construct is asked for, not inferred, so the direction
  fits the principle as written. What it must confront is the one place
  explicitness does not by itself make a design's meaning local: a net whose
  resolved value depends on drivers outside the module that declares the pin.

## Spec follow-up

**A spec rule is required if anything is adopted.** The next free requirement id
after R34 is **R35**. R35 must state whether `InOut` and `z` exist, the construct
and its legal contexts, and how it relates to R10 (module ports), R11/R28 (driven
everywhere and settles), and R18 (blackbox ports). Writing the R35 spec text is
**not** part of this task — it is required follow-up, recorded here — and no file
under `core/src/main` changes until the owner has chosen.

## Exit criteria

- [ ] The owner has decided between adding `InOut`/`z` and documenting
      split-bus; the decision and its reason are recorded in this file and the
      milestone README.
- [ ] If adopted: an `inout` port and a `z` output state exist, are emitted in
      the chosen form, and the emitted text is held by a golden.
- [ ] The interaction with R11 and R28 is stated.
- [ ] The R35 spec text is written, or the decision to change nothing — with
      split-bus documented as the supported idiom — is recorded with its reason.
- [ ] No change to `core/src/main`, `examples/src/main`, or any test beyond
      what an adopted design requires, and no memory ceiling is introduced.

## Outcome

**Deferred — not done.** At the owner's direction the decision is deferred:
M58 stays in `roadmap/open/` as an open, undecided gap. No code was written —
no `.scala`, test, or `build.sbt` file changed. The framing above is what a
future decision will need.

No exit criterion above is met, so none is ticked.

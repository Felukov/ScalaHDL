# M58 — A bidirectional pin

**Status:** open

Give the DSL a way to declare and drive a **bidirectional pin** — a port with a
direction of `inout` and a high-impedance (`z`) output state. Today a port is
`In` or `Out` and nothing else, and no expression can be high-impedance, so a
design cannot express a tri-state bus, a bidirectional pad, or a vendored IP pin
declared `inout`.

This is the second of the two expressiveness blockers a completeness analysis of
the synthesisable Verilog-2001 subset found — the first is
[R34, a runtime selection on a packed vector](../../../hdl-spec.md#a-runtime-selection-on-a-packed-bit-vector-r34). It is recorded here as a problem
with its options named; the resolution is the owner's to choose, not this
milestone's to assume.

## Why this exists

- **`Direction` has no `InOut`.** It has only `In` and `Out` —
  [`Direction.scala:4`](../../../core/src/main/scala/scalahdl/core/Direction.scala).
- **No high-impedance literal exists in the expression IR.** `Expr.Lit` carries
  a value, a width, and a kind — not a `z` state —
  [`Expr.scala:31`](../../../core/src/main/scala/scalahdl/core/Expr.scala).
- **The emitter never writes `z`.** [`VerilogEmitter.scala:849`](../../../core/src/main/scala/scalahdl/emit/VerilogEmitter.scala)
  emits references, literals, operators, muxes, selects, concatenations,
  resizes, casts, and CDC markers — no tri-state form.
- **A blackbox cannot restore the direction.** R18 lets a blackbox declare the
  ports of an external module, but its ports are typed by the same `Direction`
  enum, and there is no `InOut` for it to declare — so vendor IP with an `inout`
  pin cannot be described either.

```
  Direction:  In | Out          (no InOut)
  Expr.Lit :  value · width · kind   (no z)
  emitter  :  input | output    (never "inout", never a z assignment)
```

> Note: R7's direction-free bundles and R31's `<>` move data between endpoints
> whose directions are named once; that is about which side drives a signal, not
> about a pin that is electrically bidirectional. It does not mitigate this.

**What this blocks:** bidirectional pads, shared (tri-state) buses, and vendor
IP with an `inout` pin.

**The partial mitigation, and its limit.** The analysis recorded the common
on-chip alternative as a **split input/output plus external tri-state**: declare
a separate `Out` for the driven value and a separate `In` for the sampled value,
and put the tri-state pad (or the external `inout` pin's own buffer) outside the
emitted design. This is a *real* mitigation — on many parts the pads are already
elsewhere, and a shared bus between two internal modules can be replaced by a
mux — but it is **partial**. It cannot describe a vendor module whose pin *is*
`inout` in the emitted port list, and it moves the tri-state out of the design
rather than expressing it. The trade-off is stated here so it is visible; this
milestone does not decide whether it is the answer.

## Design framing

A read-only framing of what a future decision needs is recorded in
[R35-a-bidirectional-pin.md](R35-a-bidirectional-pin.md#design-framing): the
port model, the four no-default matches over `Direction`, the single-driver
wall, the fact that `z` has no kind, the blackbox surface, the principle
tension, and the size assessment. It resolves nothing.

## Scope

- **R35** — see [R35-a-bidirectional-pin.md](R35-a-bidirectional-pin.md). Decide
  whether the DSL gains an `InOut` direction and a tri-state (`z`) literal, or
  stays strictly unidirectional and documents split-bus as the only supported
  idiom; and, if adopted, build it.
- The decision covers the **direction**, the **`z` literal or construct**, the
  **emitter** form, and how a tri-state behaves under the existing checks
  (R11 combinational completeness, R28 cycles) that were written assuming every
  signal has exactly one driven state.

## Non-goals

- Electrical-pad modelling, drive strength, pull-ups, or any strength modelling
  beyond `0`/`1`/`z`. This is about whether `inout` and `z` are *sayable*, not
  about analog behaviour.
- Resolving multi-driver semantics. Whether two modules may drive one net, and
  what the library says about it, is part of what the decision must answer, not
  a separate task.
- Bundles gain no per-field direction; R7 is unchanged.
- The ergonomic shortcuts and deliberate exclusions already settled in the spec.
  This milestone adds none of them.

## Open questions

To be decided by the owner; each is carried into R35 rather than pre-empted.

1. **Add `InOut` and a `z` literal, or stay unidirectional?**
   - *Add them.* `Direction.InOut` and an expression for `z` make a tri-state
     bus and a bidirectional pad expressible, and let a blackbox declare a
     vendor `inout` pin. The cost is that the DSL admits a construct whose
     meaning depends on everything else on the net.
   - *Stay unidirectional.* Keep `In`/`Out`, document **split input/output plus
     external tri-state** as the only supported idiom, and record that a vendor
     `inout` pin is out of scope. The cost is the expressiveness gap above.
2. **If `InOut` is added, how does it sit with explicitness?** Explicitness says
   "structure appears in the output because the user asked for it; nothing is
   inferred". A `z` literal is asked for explicitly, which fits. But a tri-state
   net's *resolved* behaviour depends on other drivers the module may not own, so
   the design's meaning is no longer local to the module that declares the pin.
   The decision must say whether that is acceptable, and whether the library
   should require the drivers of a tri-state net to be named together.
3. **What does the emitter write, and where is it legal?** `assign pad = oe ?
   value : 8'bz;` on an `inout` port, and `z` only in a context the tool accepts.
   Which of R11's completeness and R28's cycle checks need restating for a
   signal that may be undriven by design?
4. **May a blackbox declare `InOut`?** R18 exists so vendor IP need not be
   rewritten to suit the library; an `inout` pin is ordinary in vendor IP. If the
   direction is added, does a blackbox port take it, and does R18's domain
   declaration have anything to say about it?

## Impact on the stated principles

The three non-negotiables are in [`hdl-spec.md`](../../../hdl-spec.md).

- **Correspondence.** Favourable if adopted: `inout` and `z` are the Verilog
  words a reader of the source expects to see, and the emitter writes them
  directly.
- **Locality.** Neutral on the surface; the tension is at *resolution*, not at
  renaming — a tri-state net's behaviour is not local to one module, which is
  the point Open question 2 must settle.
- **Explicitness.** The construct is asked for, not inferred, so the direction
  fits; what it must confront is that a tri-state net's *behaviour* depends on
  drivers outside the declaring module, which is the one place explicitness does
  not by itself make the design's meaning local.

## Spec follow-up

**A spec rule is required if anything is adopted.** The next free requirement id
after R34 is **R35**. R35 must state whether `InOut` and `z` exist, the construct
and its legal contexts, and how it relates to R10 (module ports), R11/R28
(driven-everywhere and settle), and R18 (blackbox ports). Writing the R35 spec
text is **not** part of this task — it is required follow-up, recorded here —
and no file under `core/src/main` changes until the owner has chosen.

## Exit criteria

- [ ] The owner has decided whether to add `InOut` and `z` or to document
      split-bus as the only idiom; the decision and its reason are recorded here
      and in R35.
- [ ] If adopted: an `inout` port and a `z` output state exist, are emitted in
      the chosen form, and the emitted text is held by a golden.
- [ ] The interaction with R11's completeness and R28's cycle checks is stated.
- [ ] The R35 spec text is written, or the decision to change nothing — with
      split-bus documented as the supported idiom — is recorded with its reason.
- [ ] No change to `core/src/main`, `examples/src/main`, or any test beyond
      what an adopted design requires, and no memory ceiling is introduced.

## Outcome

**Deferred — not done.** At the owner's direction the decision is deferred:
M58 stays in `roadmap/open/` as an open, undecided gap. No code was written —
no `.scala`, test, or `build.sbt` file changed. The design framing in R35 is
what a future decision will need; no exit criterion is met, and Status stays
`open`.

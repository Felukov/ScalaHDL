# R09 — Verilog-2001 primary; optional bit-equivalent SystemVerilog backend

**Milestone:** M11 — Emission polish
**Status:** open
**Spec:** §11, R09
**Depends on:** R17 (aggregates preserved → SV can emit real structs), R07.2
(packed-struct layout == SV layout), R20 (file organisation)
**Unblocks:** the teaching artifact (same design, two outputs, visible
difference in structure expression)

## Goal

**Verilog-2001 is the primary target and the reference semantics.** An
optional SystemVerilog mode emits the same design using real packed structs,
and may also use `logic`, `always_ff`/`always_comb`, and enums.

Both backends must be **bit-equivalent by construction** — the SystemVerilog
output is a readability improvement, never a semantic change. R07.2 makes this
nearly free, since the packing layout is already SystemVerilog's.

- The IR is backend-neutral (R17): bundles survive as bundles, and leaf-name
  resolution happens as the Verilog backend writes. Flattening during
  elaboration would make the structure unrecoverable.
- **Equivalence is testable:** golden tests generate both and assert
  identical simulation behaviour, or the modes drift.

This also yields a teaching artifact: the same design, two outputs, and a
visible difference in how structure is expressed.

## Scope for M11

- [ ] `SystemVerilog` backend reading the same IR as `Verilog2001`.
- [ ] SV emits packed structs for bundles (R07.2 layout); real arrays for
      internal Vecs (R14.1 SV column); `logic` for nets; `always_ff` for
      clocked, `always_comb` for combinational; enums where the type is an
      enum.
- [ ] V2001 stays the reference; SV is a pure readability mode.
- [ ] `test/equiv` golden tests: emit both, simulate (Verilator) against a
      hand-written testbench, assert identical behaviour.

## Non-goals for M11

- SV-only features like interfaces, modports — out of scope; bundles map to
  packed structs, not interfaces.
- Assertion emission for the handshake contract — future (R08's note).

## Exit criteria

- [ ] A design with a bundle emits a packed struct in SV and flattened leaves
      in V2001, bit-equivalent.
- [ ] Equivalence tests pass for the counter, a bundle-port module, and the
      async FIFO (once the async FIFO exists).

## Outcome

_(to fill on completion)_

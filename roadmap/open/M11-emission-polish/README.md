# M11 — Emission polish

**Status:** open

The SystemVerilog backend and the determinism/reproducibility guarantees that
make golden tests and reproducible builds real. Placed late, after the IR it
reads is stable, so the backend and the determinism rules don't churn against
earlier milestones. The V2001 emitter's bones and R20's file-per-module are
already established; this milestone deepens both.

## Scope

- **R09** — Verilog-2001 primary and reference semantics; optional
  SystemVerilog backend emitting real packed structs / `logic` /
  `always_ff`/`always_comb` / enums; both bit-equivalent by construction;
  equivalence golden-tested.
- **R20.1** — deterministic emission order (depth-first, children before
  parents, ties by declaration order; never map order).
- **R20.2** — reproducible header (library version, generator class,
  parameters; no timestamps).

## Exit criteria

- [ ] The SystemVerilog backend emits packed structs for bundles, `logic`,
      `always_ff`/`always_comb`, enums where applicable.
- [ ] V2001 and SV outputs of the same design are bit-equivalent (golden
      equivalence tests pass).
- [x] Emission order is depth-first children-before-parents; a test with
      several modules confirms the order and that adding a module doesn't
      reorder unrelated ones (`EmissionDeterminismSuite`, R20.1).
- [x] Header carries version + generator class + parameters, no timestamp;
      two runs produce identical bytes (`EmissionHeaderSuite`, R20.2).
- [ ] **The emitter owns its directory.** `Emission.writeTo` creates and
      overwrites but never deletes, so a renamed or re-parametrised module
      leaves its old file behind, and nothing but the manifest knows it is
      dead. Found 2026-09-23 in a consumer: its generated directory carried
      sixteen such files (a renamed arbiter, four modules under earlier
      parameter names, their reports), and the directory the board's
      bitstream was built from carried three more. Fix: before writing,
      `writeTo` deletes the files of its own kinds in the target — `*.v`,
      `*.rpt`, `*.fsm.md`, `manifest.f` — and leaves every other file alone
      (firmware images and memory initialisation files live beside the
      emitted Verilog and are not the emitter's). What the emitter writes,
      the emitter clears. Check: emit into a directory seeded with a stray
      `Old.v` and a `lane.hex`; afterwards the first is gone and the second
      is untouched. R20 gains that sentence when this lands.

## Outcome

_(to fill on completion)_

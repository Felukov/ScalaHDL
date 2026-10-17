# M06 — Handshake

**Status:** open

The built-in `Handshake[T]` — valid/ready/payload — generating paired
directional bundles from a single definition, with a connect operator matching
by name. Built on R07, not a core special case.

This is a single-requirement milestone, but it's the ergonomic keystone: it
recovers most of what R07's direction-free bundles cost (paired bundles for
bidirectional interfaces) and connects to the CDC story (a cross-domain
`queue` is R05's `AsyncFifo`).

## Scope

- **R08** — `Handshake[T]` master/slave, `<>` connect by name, type-checked;
  flattening is R07.1 unchanged; combinators `queue`/`map`/`fork`/`join`/
  `throttle`; a cross-domain `queue` is `AsyncFifo`.

## Exit criteria

- [x] `Handshake.master(UInt(8))` / `Handshake.slave(UInt(8))` build
      paired endpoints emitting `*_valid`, `*_ready`, `*_payload`.
- [x] `prod.out <> cons.in` connects matching handshake leaves;
      master-to-master is an elaboration error; mismatched payload types is an
      elaboration error.
- [ ] `queue(depth)`, `map`, `fork`, `join`, `throttle` exist and emit.
- [x] A `Handshake[Bundle]` emits `*_payload_<field>` (R07.1 unchanged).

## Outcome

Endpoint slice implemented: typed master/slave construction, complementary
valid/ready/payload directions, symmetric checked `<>`, exact payload-shape
checking, and recursive Bundle/Vec payload connections using the existing
aggregate paths and R07.1/R14.1 emitter. Covered by scalar and Bundle emission
and negative elaboration tests; the full test suite passes.

The milestone remains open by explicit scope decision: `queue`, `map`, `fork`,
`join`, and `throttle` are deferred to a follow-up.

**Same-domain `queue(2)` is `scalahdl.lib.Skid`** — the register slice
plustoo-soc's floppy path asked for, and the first consumer of R8's endpoints.
It exists: the module is in `core/src/main/scala/scalahdl/lib/`, and a
SystemVerilog testbench covers it. Deeper same-domain queues and the
other combinators stay deferred.

That first consumer also found a defect in what is already checked off above.
`producer.out <> slice.s` had never worked across an instance boundary: `<>`
recorded its four leaves straight into the parent's builder, and an instance's
input port is still the *child's* signal, so the three input leaves became
parent statements under the child's ids and the instance was left with three
unconnected inputs (R23's completeness check said so, which is why it failed
loudly rather than miswiring). `<>` now routes through `Assign.at`, the same
path `:=` takes, with a test here.

**A same-domain queue of any depth is `scalahdl.lib.SyncFifo`** (R32):
a power-of-two number of words, `ready` and `valid` read from its positions
only. With `Skid` for two words and `AsyncFifo` across domains, `queue(depth)`
has a module for every case; what this milestone still owes is `map`, `fork`,
`join` and `throttle`, and a `queue(depth)` spelling that picks among the three.

**The endpoint moved to R31.** `Handshake` is now a definition on the
general `Interface` — its three fields and their directions, written from the
master's side — and `Flow` stands beside it for the answer that cannot be
refused. The spelling is `Master(Handshake(t))` / `Slave(Handshake(t))`. The
milestone still owes the combinators, and nothing else.

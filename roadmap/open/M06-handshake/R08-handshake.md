# R08 — Handshake[T] with combinators

**Milestone:** M06 — Handshake
**Status:** open
**Spec:** §5, R08
**Depends on:** R07 (built on bundles), R07.1 (flattening unchanged), R11
(completeness on the generated logic), R14 (payload may be a Vec/Bundle)
**Unblocks:** R05 (cross-domain `queue` is `AsyncFifo`), the natural write
style for streaming hardware

## Goal

A built-in `Handshake[T]` (valid / ready / payload) generating paired
directional bundles from a single definition, with a connect operator matching
by name. Built on R7, not a core special case.

```scala
class Producer extends Module:
  val out = Handshake.master(UInt(8))

class Consumer extends Module:
  val in = Handshake.slave(UInt(8))

prod.out <> cons.in
```

- **Connection is checked:** master-to-master or mismatched payload types are
  elaboration errors.
- **Flattening is R7.1 unchanged:** `out_valid`, `out_ready`, `out_payload`, or
  `out_payload_addr` for a bundle payload. Nothing new in the emitter.
- **Standard combinators:** `queue(depth)`, `map`, `fork`, `join`, `throttle`.
  A cross-domain `queue` is R5's `AsyncFifo`, so the CDC story connects.

*Future:* the handshake contract (valid not retracted before ready; payload
stable while valid) is checkable, and could optionally emit SystemVerilog
assertions for simulation.

## Scope for M06

- [x] `Handshake[T]` type: `valid: Bool`, `ready: Bool`, `payload: T`.
- [x] `Handshake.master(t)` / `Handshake.slave(t)` produce paired directional
      endpoints (master drives valid + payload, receives ready; slave the
      reverse).
- [x] `<>` connects matching leaves; direction must be complementary
      (master→slave), payload types must match — else elaboration error.
- [ ] Combinators: `queue(depth)`, `map(f)`, `fork`, `join`, `throttle(n)`.
      Same-domain `queue(2)` is `scalahdl.lib.Skid` (R30) — a register
      slice is what a depth-two same-domain queue *is*, and a deeper one is a
      different structure.
- [ ] `queue` in a foreign domain routes to `AsyncFifo` (R05); for now, same
      domain uses a synchronous FIFO — which exists: `scalahdl.lib.SyncFifo`
      (R32), for any power-of-two depth.

## Non-goals for M06

- Valid/ready contract assertions — noted as future; not required for this
  version.
- Arbiters / width converters — common but not in the spec's combinator list.

## Exit criteria

- [x] A producer/consumer pair connects and emits matching port names.
- [x] master→master errors; payload-type mismatch errors.
- [ ] Each combinator emits correct, complete (R11) logic.
- [x] Bundle payload flattens per R07.1 with no special-case emitter.

## Outcome

Master/slave endpoints and checked `<>` are implemented and covered by scalar,
Bundle, reversed-call-site, and negative elaboration tests. Payload aggregate
leaves use ordinary R07/R14 paths; no handshake-specific emitter logic was
added. The full test suite passes.

Per explicit scope decision, combinators remain open for a follow-up, so R08 and
M06 are not yet closed. The one that has since arrived is R30's
`scalahdl.lib.Skid`, the same-domain `queue(2)`, built by a design rather than
for the list.

**R23 correction.** `<>` recorded its four leaves through the builder
directly. Inside one module that is what `:=` does anyway; across an instance
boundary it is not, because an instance's input port is still the child's
signal and only `Assign.at` turns a write to it into a connection on the
instance. So `producer.out <> consumer.in` between two *instances* — the shape
the goal above is written in — left three inputs unconnected and failed R23's
completeness check. `<>` now goes through `Assign.at`; the connect path is
otherwise unchanged, and the check that a bad connection is an elaboration
error is unchanged with it.

**R31.** The endpoint is no longer this requirement's own machinery.
`Handshake` is a definition on R31's `Interface` — three fields with their
directions written from the master's side — declared with `Master(Handshake(t))`
and `Slave(Handshake(t))`; `Handshake.master`/`Handshake.slave` are gone, and
the roles, the naming, the typed payload and `<>` are the interface's. `Flow`,
the same with no `ready`, arrived beside it. The three checked boxes above are
now R31's, and what stays open here is exactly the combinators.

package scalahdl.dsl

/** R08 — A valid/ready/payload endpoint, written from the master's side (R31).
  *
  * {{{
  *         master                                slave
  *    ┌───────────┐   valid   ─────────────►  ┌───────────┐
  *    │ has       │   payload ─────────────►  │ can       │
  *    │ something │   ready   ◄─────────────  │ take it   │
  *    └───────────┘                            └───────────┘
  *                  transfer: the clock both valid and ready are high
  * }}}
  *
  * The master raises `valid` with the payload beside it and holds both until
  * the slave answers `ready`; the one clock both are high is the transfer,
  * and only then may the payload change. The rule is stated here and checked
  * nowhere yet — R08's future note.
  *
  * ```scala
  * val out = Master(Handshake(UInt(8)))        // out_valid, out_ready, out_payload
  * val in  = Slave(Handshake(MemRequest()))    // in_valid, in_ready, in_payload_addr, …
  * producer.out <> consumer.in
  * ```
  *
  * This is a definition on R31 and nothing more: the three ports, their
  * directions and the `<>` that connects them are the interface's. `P` is the
  * payload's blueprint and `H` the handle it binds to — a signal for a `Type`,
  * the bundle itself for a `Bundle` — and both are inferred at the
  * declaration; nothing writes them. */
final class Handshake[P, H](p: => P)(using Blueprint[P, H]) extends Interface:
  /** The master has something. */
  val valid = Out(Bool())
  /** The slave can take it. */
  val ready = In(Bool())
  /** What it is: stable from `valid` until the transfer. */
  val payload = Out(p)

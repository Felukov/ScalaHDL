package scalahdl.dsl

/** R08 — A valid/payload endpoint: a word that cannot be refused, written
  * from the master's side (R31).
  *
  * {{{
  *         master                                slave
  *    ┌───────────┐   valid   ─────────────►  ┌───────────┐
  *    │ has       │   payload ─────────────►  │ takes it  │
  *    │ something │                            │ this clock│
  *    └───────────┘                            └───────────┘
  * }}}
  *
  * A [[Handshake]] with the `ready` left out, for the answers and the samples
  * that are taken on the clock they arrive: a memory's read word, a codec's
  * output. Declaring one says the slave has no way to refuse, where a
  * handshake with a `ready` nobody drives would say the opposite.
  *
  * ```scala
  * val rsp = Master(Flow(UInt(16)))    // rsp_valid, rsp_payload
  * ``` */
final class Flow[P, H](p: => P)(using Blueprint[P, H]) extends Interface:
  /** The master has something, and the slave takes it this clock. */
  val valid = Out(Bool())
  /** What it is. */
  val payload = Out(p)

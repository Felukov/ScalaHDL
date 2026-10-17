package scalahdl.core

import SignalId.*
import scalahdl.clock.ClockDomain

/** R12 — Driver block. The single block driving one signal: all assignments
  * to a signal collect here, in source order, regardless of how many `when`
  * blocks wrote to it. Two separate `when` blocks driving one signal would be
  * illegal Verilog; collecting into one block preserves literalness while
  * staying legal.
  *
  * `clocked` selects the sensitivity list and assignment kind:
  *   - combinational → `always @(*)` + blocking `=`
  *   - clocked        → `always @(posedge clk)` + non-blocking `<=`
  *
  * `domain` carries the clock/reset signals for the sensitivity list and the
  * reset branch (R13 — reset style is a domain property, including sync versus
  * async and the reset polarity).
  *
  * R17.1 — the block references its target by `signalId`; the signal never
  * references the block. This is the only direction, so the graph is acyclic
  * by construction and immutable after elaboration (no freeze step). */
final case class DriverBlock(
  signalId: SignalId,
  domain: ClockDomain,
  clocked: Boolean,
  stmts: List[Stmt]
)

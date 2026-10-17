package scalahdl.dsl

import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.clock.ClockDomain

/** R13.3 — `SingleClock` and `DualClock` are library traits declaring the
  * standard clock/reset ports and supplying the domain `given`. Opt-in and
  * readable, so R13.1's explicitness survives while the per-module ceremony
  * does not have to be retyped.
  *
  * Both are opt-in: a plain `Module` has no implicit clocking. Ports declared
  * by the trait use the trait's `val` names, so R1 holds. The documented
  * polarity default is active-low asynchronous reset — the common convention
  * for ASIC/FPGA designs; unusual polarities use R13.1 directly. */

/** `SingleClock` — one clock, one active-low async reset, one domain `given`.
  * A subclass body can declare `Reg`s directly without a hand-written
  * `given ClockDomain`. */
trait SingleClock extends Module:
  val clk   = In(Clock())
  val rst_n = In(Reset(ActiveLow))
  given defaultDomain: ClockDomain = domain(clk, rst_n, ResetKind.Async, ActiveLow)

/** `DualClock` — two clock/reset pairs and two named domain `given`s. The
  * body selects between them with `using clockA` / `using clockB` or
  * `withClock(clockB) { ... }`. */
trait DualClock extends Module:
  val clkA   = In(Clock())
  val rstA_n = In(Reset(ActiveLow))
  val clkB   = In(Clock())
  val rstB_n = In(Reset(ActiveLow))
  given clockA: ClockDomain = domain(clkA, rstA_n, ResetKind.Async, ActiveLow)
  given clockB: ClockDomain = domain(clkB, rstB_n, ResetKind.Async, ActiveLow)

package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module
import scalahdl.clock.{ClockDomain, ClockRef, ResetSpec, ResetKind}
import scalahdl.core.ResetPolarity

/** R13 / R13.1 / R13.2 — Clocking: the concise `ClockDomain` factory, the
  * `withClock` domain-scope, and the `inst` module-instantiation wrapper.
  *
  * The concise factory lives here (not in `clock.ClockDomain`) so `clock`
  * stays dependent only on `core` types — no cycle with `dsl`. The factory
  * takes `DslSignal`s and extracts the ids/names the `ClockDomain` record
  * carries.
  *
  * `withClock(domain) { ... }` pushes the domain onto the enclosing module's
  * builder domain stack (the same pattern as `when`'s condition stack) so
  * registers and instances declared in the block see the scoped domain. The
  * body is a context function `ClockDomain ?=> Unit` so the domain is also
  * available as the `using ClockDomain` evidence inside the block.
  *
  * The `ClockDomain` type alias re-exports `scalahdl.clock.ClockDomain` so
  * `import scalahdl.dsl.{*, given}` brings in both the factory `def` and the
  * type — user code does not need to import the `clock` package. */

/** The clock-domain record type, re-exported so the DSL factory and the type
  * are both reachable from `import scalahdl.dsl.{*, given}`. */
type ClockDomain = scalahdl.clock.ClockDomain

/** `ClockDomain(clk, rst_n, kind, polarity)`. The concise factory from
  * DslSignals: extracts ids/names so the user writes the domain from its
  * ports without spelling out the internals. The clock must be `Type.Clock`
  * and the reset `Type.Reset` — the type-level guard that a domain can only
  * be built from correctly-typed signals. */
def ClockDomain(clk: DslSignal, rst: DslSignal,
                kind: ResetKind, polarity: ResetPolarity): ClockDomain =
  require(clk.typeOf == Type.Clock,
    s"ClockDomain clock must be `Clock`, got ${clk.typeOf}")
  require(rst.typeOf.isInstanceOf[Type.Reset],
    s"ClockDomain reset must be `Reset`, got ${rst.typeOf}")
  scalahdl.clock.ClockDomain(
    ClockRef(clk.id, clk.name),
    ResetSpec(Some(rst.id), Some(rst.name), kind, polarity))

/** A domain with clock only and no reset (`ResetKind.None`). */
def ClockDomain(clk: DslSignal, kind: ResetKind): ClockDomain =
  require(clk.typeOf == Type.Clock,
    s"ClockDomain clock must be `Clock`, got ${clk.typeOf}")
  scalahdl.clock.ClockDomain(
    ClockRef(clk.id, clk.name),
    ResetSpec(None, None, kind, ResetPolarity.ActiveHigh))

/** A blackbox-created domain is available only after `inst(...)` has
  * rebound its output clock into the parent module. */
inline def withClock(domain: scalahdl.elab.BlackBoxDomain)(body: ClockDomain ?=> Unit)(
    using m: Module, default: ClockDomain): Unit =
  withClock(domain.clockDomain)(body)

/** R13.2 / R4 — `withClock(domain) { ... }` scopes a different clock domain
  * locally. Registers declared in the block record the scoped domain (R4);
  * instances auto-connect from it (R13.2). The body is a context function so
  * `using ClockDomain` resolves to the scoped domain inside the block.
  *
  * R4.2 — and the region must govern a declaration. The pop happens on both
  * paths so a failed body leaves the stack clean, but the R4.2 raise is on
  * the success path only: a region whose body already threw has a better
  * error to report than this one, and `finally` would have masked it. */
inline def withClock(domain: ClockDomain)(body: ClockDomain ?=> Unit)(
    using m: Module, default: ClockDomain): Unit =
  m.builder.pushDomain(domain, default)
  var governed = false
  try
    body(using domain)
    governed = m.builder.popDomain()
  catch
    case e: Throwable =>
      m.builder.popDomain()
      throw e
  if !governed then m.builder.governsNothing(domain)

/** Internal alias for the `ClockDomain` factory, unambiguous with the
  * `scalahdl.clock.ClockDomain` case-class companion when both are in scope
  * (e.g. inside the clocking traits). */
def domain(clk: DslSignal, rst: DslSignal,
           kind: ResetKind, polarity: ResetPolarity): ClockDomain =
  ClockDomain(clk, rst, kind, polarity)

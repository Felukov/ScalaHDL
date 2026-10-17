package scalahdl.clock

import scalahdl.core.{SignalId, Type, ResetPolarity}

/** R4 / R13 / R05.5 — Clock domain. Every signal records the domain it was
  * created in (R4); reset style is a domain property, not a per-register
  * declaration (R13).
  *
  * R05.5 — a domain is **two things**, and they are separate values because
  * they answer separate questions:
  *
  *   - [[ClockRef]] is what the domain *is*. R05 decided that a domain is its
  *     clock, so this is the identity, and `==` on it is that relation.
  *   - [[ResetSpec]] is what a register in the domain *does at reset*. A
  *     recipe, carried along; never an identity.
  *
  * They were one flat record, and case-class `==` therefore answered a
  * question nobody asked — *were these two written the same way* — which
  * agrees with "are these one domain" exactly as long as a module holds one
  * reset shape per clock. R13 exists to make that assumption false on purpose:
  * a vendored peripheral with an asynchronously reset status register beside
  * an unreset pipeline stage is the ordinary case. Three defects came out of
  * the gap, one of them a `WriteFirst` memory read silently emitting a
  * `ReadFirst` one.
  *
  * So ask the question you mean. Identity is `domain.clock`, or [[isForeignTo]]
  * for the R05 relation with its neutrality. Comparing whole domains is still
  * legitimate — two records differing in reset are genuinely different
  * *recipes* — and is now something a reader can see is being asked for. */
enum ResetKind:
  case Sync    // always @(posedge clk) if (rst) ...
  case Async   // always @(posedge clk or negedge rst_n) if (!rst_n) ...
  case None

/** R05.5 — the clock a domain *is*. Identity, and nothing else: `==` on this
  * is the relation R05 defined.
  *
  * `id` is the `SignalId` of the clock signal so the emitter can name it in a
  * sensitivity list without a lookup, and `name` is the emitted Verilog name.
  * Carrying both keeps `clock` dependent only on `core` types (no cycle with
  * `dsl`). */
final case class ClockRef(id: SignalId, name: String)

object ClockRef:
  /** The clock of a signal that carries none — ports, and combinational
    * signals with no domain. Neutral in both directions under R05. */
  val none: ClockRef = ClockRef(SignalId(-1), "clk")

  /** R05.7 — the clock of an input that has none of ours: a pin a part with
    * its own crystal drives. Not neutral, and not any of this design's clocks,
    * so every read of it into clocked logic is a crossing. */
  val asynchronous: ClockRef = ClockRef(SignalId(-2), "asynchronous")

/** R13 / R05.5 — how a register in a domain resets: the signal, its polarity,
  * and whether the edge is in the sensitivity list at all. A property of the
  * clock region, which is why it lives here and not on the register (R13), and
  * a *recipe* rather than an identity, which is why it is not what decides a
  * crossing (R05). */
final case class ResetSpec(
  id: Option[SignalId],
  name: Option[String],
  kind: ResetKind = ResetKind.Sync,
  polarity: ResetPolarity = ResetPolarity.ActiveHigh
):
  /** R13.4 — whether a register in this domain gets a reset branch to put its
    * `init` in. `ResetKind.None` has none, and neither does a kind that names
    * no reset signal — the emitter writes the same reset-free `always` block
    * for both — so the value goes on the declaration instead, as the state the
    * register comes up at. */
  def hasReset: Boolean = kind != ResetKind.None && name.isDefined

object ResetSpec:
  /** No reset at all: no signal, no branch, no edge. */
  val none: ResetSpec = ResetSpec(None, None, ResetKind.None, ResetPolarity.ActiveHigh)

final case class ClockDomain(clock: ClockRef, reset: ResetSpec):

  /** Flat accessors onto the two halves. Reading a field is not an identity
    * question, so these stay — what R05.5 moved is the *comparison*. */
  def clkId: SignalId              = clock.id
  def clkName: String              = clock.name
  def rstId: Option[SignalId]      = reset.id
  def rstName: Option[String]      = reset.name
  def resetKind: ResetKind         = reset.kind
  def polarity: ResetPolarity      = reset.polarity

  /** R13.4 — see [[ResetSpec.hasReset]]. */
  def hasReset: Boolean = reset.hasReset

  /** R05 — a domain that names no clock. Neutral in both directions, which is
    * what makes ports and combinational logic free to be read anywhere. */
  def isUnclocked: Boolean = clock == ClockRef.none

  /** R05.7 — declared asynchronous: an input whose timing this design does not
    * share. Foreign to every clock, and never a register's domain — nothing
    * can be clocked on a pin's transitions. */
  def isAsynchronous: Boolean = clock == ClockRef.asynchronous

  /** R05 — whether reading a signal of this domain inside `destination` is a
    * clock-domain crossing.
    *
    * **A domain is its clock.** Two records that name the same clock signal are
    * one clock domain however they differ in reset: R13 split a register's
    * reset in two on purpose, the shape belonging to the domain and the value
    * to the register, and one module may legitimately hold both shapes on one
    * clock. Comparing whole records made that a crossing, which is a
    * metastability claim about two registers that share an edge and can never
    * be true. Since R05.5 the comparison is on [[ClockRef]], which cannot
    * express the wrong question. */
  def isForeignTo(destination: ClockDomain): Boolean =
    !isUnclocked && !destination.isUnclocked && clock != destination.clock

object ClockDomain:
  /** Placeholder for signals that carry no clock domain (ports, combinational
    * signals). Its driver blocks emit as `always @(*)`. Never used for a
    * register. */
  val noClock: ClockDomain = ClockDomain(ClockRef.none, ResetSpec.none)

  /** R05.7 — the domain an input declared `Asynchronous` carries. A source
    * only: no register or wire is ever declared in it. */
  val asynchronous: ClockDomain = ClockDomain(ClockRef.asynchronous, ResetSpec.none)

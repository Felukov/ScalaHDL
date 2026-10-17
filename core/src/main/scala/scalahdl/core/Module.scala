package scalahdl.core

/** R17 — Module. Name, ports in declaration order, signals in source order,
  * first-class memories, submodule instances, and driver
  * blocks (one per driven signal, R12). Every node carries a `Loc`.
  *
  * The name is the final one (R16/R16.1): the class name, with the
  * parameter-derived suffixes and the group prefix already applied. */
final case class Module(
  name: String,
  ports: List[Port],
  signals: List[Signal],
  memories: List[Memory],
  instances: List[Instance],
  drivers: List[DriverBlock],
  loc: Loc,
  /** R25 — the state machines declared in this module, in declaration order.
    * Metadata like R24's `EncodingInfo`: an `Fsm` elaborates to the R22 switch
    * already present in `drivers`, and this describes it for the report and
    * the R25 checks. Nothing in validation or emission reads it. */
  fsms: List[FsmInfo] = Nil,
  /** R27 — the simulation-only blocks this module declared, in the order their
    * clocks were first named. Unlike `fsms` this is not metadata about
    * something else in the IR: nothing else holds these statements, and the
    * emitter is what reads them. They are kept apart from `drivers` because
    * every consumer of `drivers` — R11's completeness verdict, R28's cycle
    * search, R29's depth, the `reg`/`wire` decision — is asking "where does
    * this signal's value come from", and the answer must stay "nowhere" for a
    * block that assigns nothing. */
  checks: List[CheckBlock] = Nil,
  /** R5.6 — the clocks this module states share a source, one family per
    * `relatedClocks` statement (merged where statements overlap), members in
    * declaration order. A statement about the module's environment, so
    * metadata like `fsms`: the check reads it through [[foreign]], the report
    * prints it, and the emitter ignores it. */
  relatedClocks: List[List[SignalId]] = Nil
):

  /** R5.6 — whether two clocks are one family: the same clock, or both named
    * by one statement. */
  def related(a: SignalId, b: SignalId): Boolean =
    a == b || relatedClocks.exists(family => family.contains(a) && family.contains(b))

  /** R5 / R5.6 — whether reading a signal of `source` inside `destination` is a
    * clock-domain crossing *in this module*: a different clock, and not one
    * this module has declared related. [[ClockDomain.isForeignTo]] is the
    * identity half of the question and keeps R5's neutrality for unclocked
    * signals; this is the whole of it. */
  def foreign(source: scalahdl.clock.ClockDomain,
              destination: scalahdl.clock.ClockDomain): Boolean =
    source.isForeignTo(destination) && !related(source.clkId, destination.clkId)

package scalahdl.validate

import scalahdl.core.*
import SignalId.*
import scalahdl.diag.{Diagnostic, ValidationFailed, Trace, TraceConfig}
import scala.collection.mutable

/** R17 — Validation runs once after elaboration, in phases. This is the
  * phase runner: it invokes each phase in order, and the first phase that
  * produces errors halts the run by throwing `ValidationFailed` rather than
  * cascading into the next (if `y` has an unresolved width, every downstream
  * check on `y` will also fail, and reporting all of them is noise).
  *
  * The phases cover widths (R06), switch labels (R22), vector writes (R14),
  * power-up values (R13.4), completeness (R11), combinational cycles (R28),
  * clock-domain crossings (R05), blocks nothing can trigger (R12.1), and name
  * collisions (R1). Module identity (R16.1) is checked at elaboration. The
  * runner's `phases` list is the ordered set of checks to apply.
  *
  * R28 — `summaries` answers for the children this module instantiates: what a
  * parent needs to know about a child is which of its inputs reach which of its
  * outputs combinationally, and children elaborate first, so by the time a
  * parent validates that answer already exists.
  *
  * The validator reads the IR only (R17 — nothing rewrites it). Lookup
  * structures the phases want (`SignalId → Signal`, `SignalId → DriverBlock`)
  * are derived here once and passed to the phases, never stored on nodes. */
object Validator:

  /** Derived lookup structures — the validator's working set. Built once per
    * `Module`; phases read through it. `signals` is a `Vector` indexed by
    * `SignalId.value` (ids are dense, handed out in declaration order by the
    * builder). `driverOf` maps a driven signal to its single driver block. */
  final class Ctx(val module: Module,
                  val summaries: Map[String, CombGraph.ModuleSummary] = Map.empty):
    val signals: Map[SignalId, Signal] =
      module.signals.iterator.map(s => s.id -> s).toMap
    val driverOf: Map[SignalId, DriverBlock] =
      module.drivers.iterator.map(db => db.signalId -> db).toMap
    def signal(id: SignalId): Signal = signals(id)

    /** R28/R29 — the combinational dependency graph, built once on first use.
      * Lazy because it is worth building only after the phases before it have
      * passed: it reads widths through `ExprType`, and a module with an
      * unresolved literal has an earlier, better diagnostic waiting. */
    lazy val comb: CombGraph.Graph = CombGraph.build(module, summaries)

  /** Run all phases in order; throw `ValidationFailed` on the first failing
    * phase (errors within a phase batched together).
    *
    * R19.1 — on the error path the current Scala stack is captured (via
    * `Trace.capture()`) and the elaboration path is attached, so the rendered
    * `in:` / `at:` block answers "which call produced the bad hardware."
    * Capture happens here only — successful elaboration pays nothing. */
  def validate(module: Module, phases: List[Phase],
               elabPath: String = "",
               summaries: Map[String, CombGraph.ModuleSummary] = Map.empty)
              (using cfg: TraceConfig = TraceConfig.filtered): Ctx =
    val ctx = Ctx(module, summaries)
    val acc = mutable.ListBuffer.empty[Diagnostic]
    phases.foreach { phase =>
      val errs = phase.run(ctx)
      if errs.nonEmpty then
        // A failing phase halts (R17): do not run the next phase. Errors
        // within the phase batch together here.
        acc ++= errs
        throw ValidationFailed(acc.result(), Trace.capture(), elabPath)
    }
    // Returned so a caller that has already paid for the derived structures can
    // read them: `elaborate` takes R28's graph summary from here rather than
    // building the graph a second time.
    ctx

  /** A validation phase. Named for the rule it implements. */
  trait Phase:
    def run(ctx: Ctx): List[Diagnostic]

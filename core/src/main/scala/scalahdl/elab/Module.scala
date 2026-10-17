package scalahdl.elab

import scalahdl.core as core
import scalahdl.dsl.{DslSignal, Source}
import scalahdl.clock.ClockDomain
import scalahdl.diag.{Code, Diagnostic, RelatedLoc, Severity, Trace, ValidationFailed}

/** R3 — `Module` base class. The elaboration context: a fresh, local `Builder`
  * per instance, threaded to hardware factories via `using Module`. No
  * ambient global state — two modules elaborated in one process do not see
  * each other's signals.
  *
  * A subclass body runs during construction; `val`s declared in the body call
  * the DSL factories (`In`, `Out`, `UInt`, `Reg`, ...), which require
  * `using Module` and record into `this.builder`. After the body completes,
  * `elaborate()` freezes the mutable builder into an immutable IR `Module`
  * (R17) and runs validation (R17 — phases in order, a failing phase halts).
  *
  * The subclass supplies `given defaultDomain: ClockDomain = ClockDomain(clk, rst)` before
  * any `Reg` is constructed (R13.1 — explicit clock/reset).
  * `Reg` captures the `using ClockDomain` and stores it per signal (R4). */
abstract class Module(args: ModuleArg*):
  val builder: Builder = new Builder
  builder.parent = Some(this)

  private val declaredParams = args.collect { case p: ModuleParam => p }.toList
  private val declaredOverride = args.collect { case ModuleNameOverride(n) => n }.toList match
    case Nil => None
    case one :: Nil => Some(one)
    case _ => throw IllegalArgumentException("a module may have only one moduleName override")

  private val declaredPrefix = args.collect { case ModulePrefixArg(p) => p }.toList match
    case Nil => None
    case one :: Nil => Some(one)
    case many => throw IllegalArgumentException(
      s"a module may have only one modulePrefix: ${many.mkString(", ")}")

  /** The `using Module` evidence. Subclass-body `val`s see this. */
  given module: Module = this

  /** R28/R29 — this module's input-to-output combinational summary, set when
    * it elaborates and read by its parent when *it* does. */
  private[elab] var combSummary: Option[core.CombGraph.ModuleSummary] = None

  /** R16 — emitted name: explicit override, or Scala class base plus stable
    * encodings for the declared name-contributing parameters, behind the
    * group's prefix when one is declared (R16.2). An override is a whole name
    * and takes no prefix, so exactly one thing decides a name. */
  def moduleName: String =
    declaredOverride.getOrElse {
      val suffix = declaredParams.flatMap(_.encoded)
      val derived =
        if suffix.isEmpty then baseModuleName else s"${baseModuleName}_${suffix.mkString("_")}"
      declaredPrefix.fold(derived)(p => s"${p}_$derived")
    }

  private[elab] def baseModuleName: String = getClass.getSimpleName.nn.replace("$", "")

  /** Stable metadata for R20.2. The runtime class name identifies the actual
    * top generator; only ordered R16 encodings are exposed as parameters. */
  private[elab] def generationInfo: core.GenerationInfo =
    core.GenerationInfo(getClass.getName.nn, declaredParams.flatMap(_.encoded))

  /** Equality key used by design-level deduplication. Non-contributing values
    * deliberately remain here even though they are absent from `moduleName`. */
  private[elab] def moduleIdentity: (String, List[(String, Any)]) =
    baseModuleName -> declaredParams.map(p => p.name -> p.value)

  /** Elaborate the accumulated builder state into an immutable IR `Module`,
    * then run validation. Called after the subclass body has run. Throws
    * `ValidationFailed` if any phase produced errors (R17).
    *
    * R19.1 — the elaboration path names the module being elaborated in the
    * `in:` form, `Mod (Mod)`. A generator is elaborated from its own body, so
    * the path names one module. */
  def elaborate(): core.Module =
    // R07.2 — an aggregate read becomes its packed bits before anything looks
    // at the module. Verilog-2001 has no name for a whole bundle, so a read of
    // one is the concatenation of its leaves; the assignments and connections
    // that carry an aggregate *whole* keep it (R17), which is what leaves R14
    // a shape to check.
    val m = scalahdl.core.AggregateRead.lower(
      builder.freeze(moduleName, scalahdl.core.Loc.NoLoc))
    val elabPath = elabPathString()
    val childSummaries = builder.children.iterator
      .flatMap(child => child.combSummary.map(child.moduleName -> _)).toMap
    val ctx = scalahdl.validate.Validator.validate(
      m, scalahdl.Module.validationPhases, elabPath, childSummaries)
    // R28 — what this module's parent will need to know about it. Children
    // elaborate first (see `elaborateDesign`), so a parent always finds the
    // answer already computed; a module elaborated on its own leaves its
    // children unanalysed, and the graph records that assumption rather than
    // making it silently.
    combSummary = Some(ctx.comb.summary)
    m

  /** The elaboration path in R19.1's `in:` form: the module name, with its
    * name in parens — `Mod (Mod)`. The path names one module because a
    * generator is elaborated from its own body, not from a parent's. */
  private def elabPathString(): String =
    s"$moduleName ($moduleName)"

  /** Elaborate the complete reachable hierarchy, post-order. Modules sharing
    * a name and identity deduplicate; sharing a name with unequal identity is
    * the R16.1 identity conflict. */
  def elaborateDesign(): core.Design =
    val byName = scala.collection.mutable.LinkedHashMap.empty[String, (Module, core.Module)]

    val externalByName = scala.collection.mutable.LinkedHashMap.empty[String, BlackBox]

    def checkExternal(box: BlackBox): Unit =
      externalByName.get(box.moduleName) match
        case None => externalByName(box.moduleName) = box
        case Some(first) if first.blackBoxSignature == box.blackBoxSignature => ()
        case Some(first) =>
          val diagnostic = Diagnostic(
            severity = Severity.Error,
            code = Code.BLACKBOX_NAME_CONFLICT,
            summary = s"blackbox module name `${box.moduleName}` has conflicting port lists",
            primaryLoc = core.Loc.NoLoc,
            related = List(RelatedLoc(core.Loc.NoLoc,
              s"first declaration: ${first.blackBoxSignature.map(_.name).mkString(", ")}")),
            fix = Some("use identical ordered external port names, directions, and types, or choose a distinct moduleName"))
          throw ValidationFailed(List(diagnostic), Trace.capture(),
            s"${box.moduleName} (${box.moduleName})")

    def visit(current: Module): core.Module =
      current.builder.blackBoxes.foreach(checkExternal)
      current.builder.children.foreach(visit)
      val frozen = current.elaborate()
      byName.get(current.moduleName) match
        case None => byName(current.moduleName) = current -> frozen
        case Some((first, _)) if first.moduleIdentity == current.moduleIdentity => ()
        case Some((first, _)) =>
          val differing = first.moduleIdentity._2.zipAll(current.moduleIdentity._2, "" -> (), "" -> ())
            .collect { case ((ln, lv), (rn, rv)) if ln != rn || lv != rv =>
              if ln == rn then ln else s"$ln/$rn"
            }
          val detail = if differing.nonEmpty then differing.mkString(", ") else "constructor parameters"
          val diagnostic = Diagnostic(
            severity = Severity.Error,
            code = Code.MODULE_IDENTITY_CONFLICT,
            summary = s"module name `${current.moduleName}` has conflicting identities (differing: $detail)",
            primaryLoc = core.Loc.NoLoc,
            related = List(RelatedLoc(core.Loc.NoLoc,
              s"first `${first.baseModuleName}` identity claimed this emitted name")),
            fix = Some("make the differing parameter name-contributing with namedParam(...), or set a distinct moduleName(...)"))
          throw ValidationFailed(List(diagnostic), Trace.capture(),
            s"${current.moduleName} (${current.moduleName})")
      frozen

    val top = visit(this)
    core.Design(top, byName.valuesIterator.map(_._2).toList, generationInfo)
end Module

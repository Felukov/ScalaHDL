package scalahdl.elab

import scala.collection.mutable
import scalahdl.core.*
import SignalId.*
import scalahdl.dsl.{DslSignal, Source}
import scalahdl.clock.{ClockDomain, ClockRef}
import scalahdl.diag.{Code, Diagnostic, RelatedLoc, Severity, ValidationFailed}

/** R3 — Typed elaboration context. No ambient global state: hardware
  * construction requires a `using Module` context, and the context is a
  * *local* mutable builder threaded explicitly via `using`, not a thread-local.
  * Two modules elaborated in the same process do not see each other's state.
  *
  * The builder accumulates, in evaluation (source) order:
  *   - ports (`In`/`Out`) and signals (`UInt`/`Reg`/...) — R10, R17
  *   - per-signal driver-block statement lists — R12 (one signal, one block)
  *   - a scope stack so `when`/`elsewhen`/`otherwise` (R12) and
  *     `switch`/`is`/`default` (R22) nest correctly, in any combination
  *   - per-signal clock domain (R4 — every signal records its domain)
  *
  * Scope is internal builder state, not a separate `using` capability (R3):
  * nobody writes a helper whose *type* says it must be called inside a `when`.
  * R22 makes the same choice deliberately for `is`, whose scope is dynamic so
  * that an ordinary Scala loop can enlist alternatives — and pays for it with
  * a diagnostic (`SWITCH_ARM_CONTEXT`) where a lexical design would have had a
  * type error. */
final class Builder:
  import Builder.*

  private val _ports     = mutable.ListBuffer.empty[Port]
  private val _signals   = mutable.ListBuffer.empty[Signal]
  private val _drivers   = mutable.ListBuffer.empty[DriverBlock]
  private val _instances = mutable.ListBuffer.empty[Instance]
  private val _memories  = mutable.ListBuffer.empty[MemoryBuilder]
  private val signalHandles = mutable.Map.empty[SignalId, DslSignal]

  /** R18.1 — External declarations reached from this module, in instance
    * order. They are checked design-wide but never emitted as module bodies. */
  val blackBoxes = mutable.ListBuffer.empty[BlackBox]

  /** R13.2 — child modules created by `inst(new Child)`, in creation order.
    * `elaborateDesign` walks this to elaborate and collect each child. */
  val children = mutable.ListBuffer.empty[scalahdl.elab.Module]

  /** R23 — per-instance record of what the instance is connecting to: the
    * child's ordered port list, the data inputs that must be connected before
    * this module freezes, and the child module name for diagnostics.
    *
    * Connections are kept in the child's declaration order rather than the
    * order the parent wrote them (R20.1), so this is what `connectInstance`
    * sorts against. `explicitConns` used to live here as a set of overridden
    * port names that nothing ever wrote; last-wins replacement in
    * `connectInstance` supersedes it and makes R13.2's promised explicit
    * override actually work. */
  private val instancePorts = mutable.Map.empty[Int, InstancePorts]

  /** R6.3 — each instance's port types, by port name. A connection is the
    * parent assigning the child's input, so the child's port type is the target
    * width a whole-RHS literal takes. Kept separately from `instancePorts`
    * because blackboxes need it too and deliberately do not record the rest:
    * an external declaration has no body to leave floating, so its inputs are
    * not tracked as required connections (R18/R23). */
  private val instancePortTypes = mutable.Map.empty[Int, Map[String, Type]]

  /** R13.6 — what an instance needs in order to restate its domain when its
    * clock is connected explicitly.
    *
    * A port carries `noClock` (R13.1), so a child's own view of its outputs is
    * neutral and the parent-side net `<inst>_<port>` is the *only* place a
    * cross-module crossing is decided. R13.2 promised the explicit override and
    * delivered it for the wire but not for the fact: `connectInstance` replaced
    * the connection and left those nets tagged with the domain in scope at
    * `inst(...)`. Recorded here so the connection can reach them. */
  private case class InstanceClocking(
    enclosing: ClockDomain,
    outputNets: List[SignalId],
    clockPorts: List[String],
    resetPorts: List[String])
  private val instanceClocking = mutable.Map.empty[Int, InstanceClocking]

  /** R5.6 — the clocks this module has stated share a source: one family per
    * statement, statements that name a common clock merged into one, members
    * in the order they were first named. */
  private var relatedClockFamilies: List[List[SignalId]] = Nil

  /** R5.6 — record a `relatedClocks` statement. The operands have already been
    * checked to be this module's own `Clock` signals. */
  private[scalahdl] def addRelatedClocks(clocks: List[SignalId]): Unit =
    val (touching, apart) =
      relatedClockFamilies.partition(family => family.exists(clocks.contains))
    val merged = (touching.flatten ++ clocks).distinct
    relatedClockFamilies = apart :+ merged

  private def relatedIn(a: SignalId, b: SignalId): Boolean =
    a == b || relatedClockFamilies.exists(f => f.contains(a) && f.contains(b))

  /** R5.6 — the families as the parent sees them: by port name, input clocks
    * only, and only the families with two or more such members. A `Wire(Clock)`
    * in a family is the module's own business; a port is a claim about what it
    * is handed, and the parent is who hands it. */
  private[scalahdl] def relatedClockPortFamilies: List[List[String]] =
    val inputClock = _ports.filter(p => p.direction == Direction.In && p.typeOf == Type.Clock)
      .flatMap(p => signalIdForPort(p.name).map(_ -> p.name)).toMap
    relatedClockFamilies.map(_.flatMap(inputClock.get)).filter(_.sizeIs >= 2)

  /** R5.6 — per instance, the child's port families, so the parent can be held
    * to them when it freezes. */
  private val instanceRelatedClocks = mutable.Map.empty[Int, List[List[String]]]

  /** R5.6 — a child that declares two of its input clocks one source has
    * stated a fact about its environment, and this module is that environment.
    * Decided at freeze, once every connection has been written, the way a
    * floating input is: each family's pins must be connected from clocks this
    * module has itself declared related (or from one clock). R13.2's
    * auto-connection counts as a connection — two pins left to it both carry
    * the enclosing clock, which is one clock. */
  private def brokenClockContracts: List[scalahdl.diag.Diagnostic] =
    instanceRelatedClocks.toList.sortBy(_._1).flatMap { case (index, families) =>
      val instance = _instances(index)
      val ports = instancePorts(index)
      val conns = instance.connections.toMap
      def connected(port: String): Option[Signal] =
        conns.get(port).collect { case Expr.Ref(id) => id }.flatMap(id => _signals.find(_.id == id))
      families.flatMap { family =>
        val handed = family.flatMap(port => connected(port).map(port -> _))
        val clocks = handed.map(_._2).distinctBy(_.id)
        val unrelated = clocks.combinations(2).exists {
          case List(a, b) => !relatedIn(a.id, b.id)
          case _          => false
        }
        if !unrelated then Nil
        else
          val pins = handed.map { case (port, clk) => s"`$port` from `${clk.name}`" }
          List(scalahdl.diag.Diagnostic(
            severity = scalahdl.diag.Severity.Error,
            code = scalahdl.diag.Code.RELATED_CLOCKS_REQUIRED,
            summary = s"`${instance.name}` (${ports.moduleName}) declares ${family.map(p => s"`$p`").mkString(", ")} " +
              s"one source, and this module connects ${pins.mkString(", ")}, which it has not",
            primaryLoc = ports.loc,
            related = List(scalahdl.diag.RelatedLoc(ports.loc,
              s"`${instance.name}` instantiated here")),
            fix = Some(s"if ${clocks.map(c => s"`${c.name}`").mkString(" and ")} share a source, say so: " +
              s"`relatedClocks(${clocks.map(_.name).mkString(", ")})`; otherwise connect the pins from one family")))
      }
    }

  /** R13.6 — restate an instance's domain from the clock actually connected to
    * it, and re-tag the nets it drives.
    *
    * Only where the child declares exactly one input clock. With two there is
    * no single answer to which of them an output belongs to — the child says,
    * and the parent cannot — so the enclosing domain stays as the conservative
    * status quo rather than half of it being guessed at.
    *
    * The reset follows the connected reset where the child has exactly one,
    * and is otherwise the enclosing domain's. It decides nothing either way:
    * a domain is its clock (R05.5), and these nets are wires with no reset
    * branch to emit. */
  private def retagInstanceDomain(index: Int): Unit =
    instanceClocking.get(index).foreach { state =>
      if state.clockPorts.sizeIs == 1 && state.outputNets.nonEmpty then
        val conns = _instances(index).connections.toMap
        def connected(port: String): Option[Signal] =
          conns.get(port).collect { case Expr.Ref(id) => id }
            .flatMap(id => _signals.find(_.id == id))
        connected(state.clockPorts.head).foreach { clk =>
          val reset = state.resetPorts match
            case single :: Nil =>
              connected(single).fold(state.enclosing.reset)(rst =>
                state.enclosing.reset.copy(id = Some(rst.id), name = Some(rst.name)))
            case _ => state.enclosing.reset
          val domain = ClockDomain(ClockRef(clk.id, clk.name), reset)
          state.outputNets.foreach { id =>
            val at = _signals.indexWhere(_.id == id)
            if at >= 0 then _signals(at) = _signals(at).copy(domain = domain)
            domains(id) = domain
          }
        }
    }

  /** R23 — per-aggregate-signal re-binding, registered by `Bundle.bind`. A
    * Bundle port bound as a submodule output has its root rebound to a
    * parent-side net; its field leaves are separate handles bound once at
    * construction, so they must be re-pointed at the same time or they keep
    * the child's signal id. The second argument names the driving instance
    * when the rebind is an instance output, so leaves reject assignment. */
  private val aggregateRebinds =
    mutable.Map.empty[SignalId, (DslSignal, Option[String]) => Unit]

  private[scalahdl] def registerAggregate(
      id: SignalId, rebind: (DslSignal, Option[String]) => Unit): Unit =
    aggregateRebinds(id) = rebind

  private[scalahdl] def rebindAggregate(
      id: SignalId, newRoot: DslSignal, drivenBy: Option[String]): Unit =
    aggregateRebinds.get(id).foreach(_(newRoot, drivenBy))

  /** R23 — the same for an aggregate bound as an instance *input*. Nothing is
    * re-pointed: the port keeps the child's signal, and what the leaves gain is
    * the knowledge that they are leaves of a pin. */
  private val aggregateInputBinds =
    mutable.Map.empty[SignalId, (Builder, Int, String) => Unit]

  private[scalahdl] def registerAggregateInput(
      id: SignalId, bind: (Builder, Int, String) => Unit): Unit =
    aggregateInputBinds(id) = bind

  private[scalahdl] def bindAggregateInstanceInput(
      id: SignalId, parent: Builder, index: Int, portName: String): Unit =
    aggregateInputBinds.get(id).foreach(_(parent, index, portName))

  /** R13.2 — Public access to the port list (for `inst` to read the child's
    * clock/reset ports and auto-connect them). */
  def portsList: List[Port] = _ports.toList

  /** R18.2 — Direction lookup used while validating declaration-side blackbox
    * domain metadata. The port records the signal it was declared onto, so the
    * lookup does not depend on the two lists being the same length. */
  private[scalahdl] def portDirection(id: SignalId): Option[Direction] =
    _ports.find(_.signalId == id).map(_.direction)

  /** R18.1 — Rename a declared port for an external module while retaining its
    * SignalId. This operation is intentionally available only during blackbox
    * declaration, before the builder is frozen. */
  private[scalahdl] def renamePort(id: SignalId, externalName: String): Unit =
    require(externalName.nonEmpty, "external port name must be non-empty")
    val portIndex = _ports.indexWhere(_.signalId == id)
    require(portIndex >= 0, "named may only be applied to a port")
    val signalIndex = _signals.indexWhere(_.id == id)
    require(signalIndex >= 0, "named may only be applied to a port")
    val oldName = _signals(signalIndex).name
    _ports(portIndex) = _ports(portIndex).copy(name = externalName)
    _signals(signalIndex) = _signals(signalIndex).copy(name = externalName)
    byName.remove(oldName)
    byName(externalName) = id

  /** Per-signal accumulated statements, in source order. Flushed into a
    * `DriverBlock` at freeze time. Keyed by SignalId. */
  private val stmts = mutable.LinkedHashMap.empty[SignalId, mutable.ListBuffer[Stmt]]

  /** Per-signal clock domain (R4). Set when the signal is declared. */
  private val domains = mutable.LinkedHashMap.empty[SignalId, ClockDomain]

  /** R27 — the open `simOnly` regions, innermost last. Non-empty means every
    * declaration and every assignment is refused: what is being elaborated is
    * a claim about the design and not part of it.
    *
    * A depth rather than a flag, because `simOnly` nests through `when` and
    * through an ordinary Scala helper called from inside one, and the region
    * has to close exactly as many times as it opened.
    *
    * It is deliberately *not* a `Scope` on `scopeStack`: a scope decides where
    * a statement is recorded, and a `simOnly` decides nothing of the kind — its
    * statements are filed under the check block for the domain, and a `when`
    * written outside it and closing around it must still see the arm it left
    * open. */
  private val simOnlyRegions = mutable.ListBuffer.empty[(ClockDomain, Loc)]

  /** R27 — one check block per clock domain, keyed by the domain, in the order
    * the domains were first named. The value is the synthetic `SignalId` its
    * statements are filed under and the `simOnly` that opened it.
    *
    * One block per domain rather than one per `simOnly` region, for the reason
    * R12 collects a signal's assignments into one block: two regions on one
    * clock are two `always` blocks that could not be told apart in the output,
    * and their statements have an order, which a single block keeps. */
  private val checkBlocks = mutable.LinkedHashMap.empty[ClockDomain, (SignalId, Loc)]

  /** The elaboration scope stack. The head is the innermost open scope: a
    * `when` arm (R12) or a `switch` alternative (R22). `:=` records into the
    * head's current sink, and a scope records its finished statement into the
    * next one out as it closes.
    *
    * One stack rather than two, so the constructs interleave: a `when` opened
    * inside a case arm lands in that arm, and a `switch` opened inside a
    * branch lands in that branch. */
  private val scopeStack = mutable.ListBuffer.empty[Scope]

  /** The `when` chain currently being built, and the one just closed.
    *
    * A chain closes at the end of `when(...) { ... }` because Scala cannot
    * tell the elaborator that an `.elsewhen`/`.otherwise` is coming. So
    * `otherwise` *reopens*: the statements the close recorded are removed
    * again (`ChainLink.undo`), the frames are pushed back, and the innermost
    * switches to its else arm. Without that, the else body lands in the
    * enclosing scope as an unconditional assignment — which is what it used to
    * do, silently turning `when(c) { y := a }.otherwise { y := b }` into
    * `if (c) y = a; y = b;` (R12). */
  /** Open chains, innermost first — chains nest, because a `when` body may
    * contain another `when`. Only the innermost is ever being closed. */
  private val openChains = mutable.ListBuffer.empty[OpenChain]
  private var lastChain: Option[ClosedChain] = None
  private var chainSeq: Int = 0

  /** One open `withClock` scope: the domain it names, the domain that was in
    * scope where it opened, and whether any declaration resolved through it.
    *
    * `enclosing` exists for R4.3. A declaration inside the region carries a
    * `using ClockDomain` of its own, and it is *usually* the scoped domain —
    * `withClock` applies its body as `body(using domain)`. It is legitimately
    * the enclosing one where the declaration comes from a helper that closed
    * over the module's given at its definition site, which is why the region
    * has to remember what that was rather than reject everything unequal. */
  private final class DomainScope(val scoped: ClockDomain, val enclosing: ClockDomain):
    /** R4.2 — set by `activeDomain`: something in this region asked. */
    var governed: Boolean = false

  /** R13.2 / R4 — the `withClock` domain stack. The head is the innermost
    * `withClock`. `Reg` and `inst` declared inside a `withClock` block record
    * the scoped domain rather than the module's default. Mirrors `condStack`
    * for the same reason: scoping is internal builder state, not a separate
    * `using` capability (R3). */
  private val domainStack = mutable.ListBuffer.empty[DomainScope]

  /** Push a `withClock(domain)` scope. `enclosing` is the `using ClockDomain`
    * at the call site — the domain the region is nested in. */
  def pushDomain(d: ClockDomain, enclosing: ClockDomain): Unit =
    domainStack.prepend(DomainScope(d, enclosing))

  /** Pop the innermost `withClock` scope, reporting whether it governed a
    * declaration (R4.2). A nested region that governed something counts for
    * its parent: `withClock(a) { withClock(b) { Reg(...) } }` states `a` for
    * `b`'s region, which is a declaration about the outer one too. */
  def popDomain(): Boolean =
    val scope = domainStack.remove(0)
    if scope.governed then domainStack.headOption.foreach(_.governed = true)
    scope.governed

  /** R4.2 — the region named a domain and nothing took it. Raised by
    * `withClock` after its body completes, not from the `finally`, so an
    * exception already in flight is never masked by this one. */
  def governsNothing(domain: ClockDomain): Nothing =
    throw ValidationFailed.one(Diagnostic(
      severity = Severity.Error,
      code = Code.DOMAIN_SCOPE_EMPTY,
      summary = s"this clock region names `${domain.clock.name}` and no declaration takes it",
      primaryLoc = Loc.NoLoc,
      related = List(RelatedLoc(Loc.NoLoc,
        "a domain is a property of declared hardware; statements do not carry one, " +
        "so a region holding only assignments states nothing")),
      fix = Some("delete the region, or move the declaration it was meant to govern inside it; " +
                 "to put one register in another domain write `Reg(..., domain = d)`")))

  /** The active domain: the innermost `withClock` if any, else the `default`
    * `using ClockDomain`. This is what `Reg` and `inst` read so `withClock`
    * overrides the domain without a lexical given swap.
    *
    * R4.3 — and it is where a *third* domain is caught. Inside a region the
    * stack wins over the lexical given, silently, which is right for a helper
    * that closed over an enclosing default and wrong for a `given` written
    * inside the block to mean it. The two are told apart by what the given
    * holds: a domain some enclosing scope also names is the first, a domain
    * no open scope has heard of is the second. */
  def activeDomain(default: ClockDomain): ClockDomain =
    domainStack.headOption match
      case None => refuseAsynchronous(default)
      case Some(scope) =>
        if !domainStack.exists(s => s.scoped == default || s.enclosing == default) then
          throw ValidationFailed.one(Diagnostic(
            severity = Severity.Error,
            code = Code.DOMAIN_SCOPE_SHADOWED,
            summary = s"this declaration's `given ClockDomain` names `${default.clock.name}`, " +
                      s"but the enclosing clock region names `${scope.scoped.clock.name}` and wins",
            primaryLoc = Loc.NoLoc,
            related = List(RelatedLoc(Loc.NoLoc,
              "the region is builder state and the given is lexical evidence; the region is " +
              "consulted first, so the given would be ignored rather than applied")),
            fix = Some("name the domain on the declaration — `Reg(..., domain = d)` — " +
                       "or nest a `withClock(d) { ... }` region instead of a `given`")))
        scope.governed = true
        refuseAsynchronous(scope.scoped)

  /** R4.1 — the domain a declaration lands in, given what it named itself.
    *
    * `explicit` is the `domain =` argument of the one call the user wrote, or
    * `null` where they wrote none. Innermost wins, which is the precedence the
    * mechanism already has: the declaration's own argument over a `withClock`
    * scope over the `using ClockDomain` default. A declaration that names
    * nothing resolves exactly as it did before R4.1, through `activeDomain`. */
  def declaredDomain(explicit: ClockDomain, default: ClockDomain): ClockDomain =
    if explicit != null then refuseAsynchronous(explicit) else activeDomain(default)

  /** R05.7 — the domain an asynchronous input carries is a source and never a
    * destination: it stands for a pin's own transitions, and nothing in this
    * design has an edge on those. It reaches a declaration only by being
    * written there by hand — as a `given`, a region, or a `domain =` argument
    * — and that is refused here rather than emitted as a register on a clock
    * that does not exist. Ports are unaffected: a port names its domain
    * directly and never asks this. */
  private def refuseAsynchronous(domain: ClockDomain): ClockDomain =
    if !domain.isAsynchronous then domain
    else throw ValidationFailed.one(Diagnostic(
      severity = Severity.Error,
      code = Code.ASYNCHRONOUS_DOMAIN_DECLARED,
      summary = "`ClockDomain.asynchronous` is the domain of a pin nothing in this design " +
                "clocks, and a declaration cannot land in it",
      primaryLoc = Loc.NoLoc,
      related = List(RelatedLoc(Loc.NoLoc,
        "it marks an input — `In(Bool(), Asynchronous)` — so that reading one is a " +
        "crossing; a register in it would be clocked on the pin's transitions")),
      fix = Some("declare the register in the domain that reads the pin, and read the pin " +
                 "itself through SyncChain")))

  /** R19.1 — the enclosing elaboration module, for hierarchy diagnostics. */
  var parent: Option[scalahdl.elab.Module] = None

  /** Map a declared name to its signal id — R1 name lookup. Collision *checking*
    * is the NamePhase's: the builder does not throw on a duplicate, it records
    * both signals in the IR so the validator can report the collision with both
    * declaration locations. Last-wins for lookup. */
  private val byName = mutable.Map.empty[String, SignalId]

  private var _nextId: Int = 0
  private def nextId(): SignalId = { val id = _nextId; _nextId += 1; SignalId(id) }

  // ---- construction entry points (called by dsl factories) ----

  /** Declare a port (R10). Name comes from R1 `Source`. Ports are not clocked
    * logic, so they carry no clock domain (their driver blocks, if any, are
    * combinational). The domain stored is a no-clock placeholder.
    *
    * R31 — `onto` is a handle that already exists and becomes this port's. An
    * interface field's `val` is bound while the interface is constructed,
    * before any port has a name, so the handle it holds is made first and the
    * port is declared onto it here. It is then the one handle the builder
    * knows for the signal, and everything R23 does to a port's handle when the
    * module is instantiated — re-pointing an output at the parent-side net,
    * telling an input which pin it is — reaches the field. */
  def addPort(src: Source, dir: Direction, t: Type,
              encoding: Option[EncodingInfo] = None,
              onto: Option[DslSignal] = None,
              asynchronous: Boolean = false): DslSignal =
    val name = resolveName(src, t)
    // R13.1/R05.7 — a port is neutral, which is what makes a module's boundary
    // free; an input *declared* asynchronous is the exception, and carries a
    // domain foreign to every clock so that reading it is a crossing.
    val signal = declareSignal(name, t, SignalKind.Wire, init = None, loc = src.loc,
      domain = if asynchronous then ClockDomain.asynchronous else ClockDomain.noClock,
      encoding = encoding, onto = onto)
    // R10 — the port records the signal it was declared onto. The two lists are
    // appended in step here, but a module whose body declares a wire between two
    // ports (a base class's, for instance) keeps them the same length and not the
    // same order, so nothing may read one by the other's position.
    _ports += Port(name, dir, t, src.loc, signal.id)
    signal

  /** Declare an internal wire (R2: only named vals get a declaration). */
  def addWire(src: Source, t: Type, domain: ClockDomain,
              encoding: Option[EncodingInfo] = None): DslSignal =
    val name = resolveName(src, t)
    declareSignal(name, t, SignalKind.Wire, init = None, loc = src.loc, domain, encoding)

  /** Declare a library-internal wire with a synthetic unique name (R2: the
    * wire is named, so it gets a declaration, but the name is minted by the
    * builder, not captured from a user `val`). Used by helpers that declare
    * intermediates not bound to a user `val` — `Mux` inside a loop — where a
    * captured name would collide. The name is `<hint>_<seq>`. */
  def addSyntheticWire(hint: String, t: Type, domain: ClockDomain): DslSignal =
    val name = s"$hint${_nextId}"   // unique per call; _nextId is the next signal id
    declareSignal(name, t, SignalKind.Wire, init = None, loc = Loc.NoLoc, domain)

  /** R05 — Declare a library-owned synchronizer stage in the destination
    * domain. Synthetic stages are real named registers in emitted hardware. */
  def addSyntheticReg(hint: String, t: Type, domain: ClockDomain): DslSignal =
    val name = s"$hint${_nextId}"
    declareSignal(name, t, SignalKind.Reg, init = None, loc = Loc.NoLoc, domain)

  /** Declare a register (R13: reset *value* here; reset style from domain). */
  def addReg(src: Source, t: Type, init: Option[Expr], domain: ClockDomain,
             encoding: Option[EncodingInfo] = None,
             powerUp: Option[Expr] = None): DslSignal =
    val name = resolveName(src, t)
    declareSignal(name, t, SignalKind.Reg, init, loc = src.loc, domain, encoding, powerUp)

  /** R18 — Instantiate an external leaf. Every output receives a parent-side
    * wire and named connection; inputs are bound so `box.port := expr` updates
    * this instance rather than creating a driver in the declaration object.
    *
    * `enclosing` is the domain in scope at the instantiation, which is what an
    * output declared with R18.4's `enclosingDomain` is bound to. */
  def addBlackBox(child: BlackBox, enclosing: ClockDomain, src: Source): Unit =
    val instName = resolveName(src, Type.Bool)
    checkNotInSimOnly(s"external instance `$instName` is created",
      "instantiate it outside the block", src.loc)
    val ports = child.builder.portsList
    val newDomains = child.blackBoxNewDomains
    val outputDomains = child.blackBoxOutputDomains
    val missingClocks = ports.filter(p => p.direction == Direction.Out && p.typeOf == Type.Clock)
      .filterNot(p => child.builder.signalIdForPort(p.name).exists(newDomains.contains))
    val missingData = ports.filter(p => p.direction == Direction.Out && p.typeOf != Type.Clock)
      .filterNot(p => child.builder.signalIdForPort(p.name).exists(outputDomains.contains))
    if missingClocks.nonEmpty || missingData.nonEmpty then
      val missing = (missingClocks ++ missingData).map(_.name).mkString(", ")
      throw scalahdl.diag.ValidationFailed(List(scalaHdlBlackBoxDiagnostic(
        scalahdl.diag.Code.BLACKBOX_DOMAIN_REQUIRED,
        s"blackbox `${child.moduleName}` requires domain declarations for outputs: $missing")))

    blackBoxes += child
    val instanceIndex = _instances.length
    instancePortTypes(instanceIndex) = ports.map(p => p.name -> p.typeOf).toMap
    val outputConns = mutable.ListBuffer.empty[(String, Expr)]
    val parentDomains = mutable.Map.empty[BlackBoxDomain, ClockDomain]

    // R18.4 — a box that joins the clock it is instantiated in rather than
    // creating one. Bound before the outputs are walked, for the same reason
    // the clock outputs are: an output resolves its declaration here.
    child.blackBoxEnclosingDomain.foreach { declaration =>
      declaration.bind(enclosing)
      parentDomains(declaration) = enclosing
    }

    // Clocks first so data outputs can resolve their declared domain.
    ports.filter(p => p.direction == Direction.Out && p.typeOf == Type.Clock).foreach { p =>
      val childId = child.builder.signalIdForPort(p.name).get
      val parentSignal = addSyntheticWire(s"${instName}_${p.name}_", p.typeOf, ClockDomain.noClock)
      val domain = ClockDomain(
        scalahdl.clock.ClockRef(parentSignal.id, parentSignal.name),
        scalahdl.clock.ResetSpec.none)
      val declaration = newDomains(childId)
      declaration.bind(domain)
      parentDomains(declaration) = domain
      child.builder.signalHandle(childId).foreach { handle =>
        handle.bindPath(parentSignal.id, parentSignal.name, false, Nil)
        handle.bindInstanceOutput(instName)
      }
      outputConns += p.name -> parentSignal.ref
    }
    // R13.6 — only the outputs that declared R18.4's `enclosingDomain` follow
    // the clock this box is wired to. A `newDomain` output is a clock the box
    // *creates* (a PLL), which is unrelated to the one it is given.
    val enclosingNets = mutable.ListBuffer.empty[SignalId]
    ports.filter(p => p.direction == Direction.Out && p.typeOf != Type.Clock).foreach { p =>
      val childId = child.builder.signalIdForPort(p.name).get
      val declaration = outputDomains(childId)
      val domain = parentDomains(declaration)
      val parentSignal = addSyntheticWire(s"${instName}_${p.name}_", p.typeOf, domain)
      if child.blackBoxEnclosingDomain.exists(_ eq declaration) then
        enclosingNets += parentSignal.id
      child.builder.signalHandle(childId).foreach { handle =>
        handle.bindPath(parentSignal.id, parentSignal.name, false, Nil)
        handle.bindInstanceOutput(instName)
      }
      outputConns += p.name -> parentSignal.ref
    }

    instanceClocking(instanceIndex) = InstanceClocking(
      enclosing, enclosingNets.toList,
      ports.filter(p => p.direction == Direction.In && p.typeOf == Type.Clock).map(_.name),
      ports.filter(p => p.direction == Direction.In &&
        p.typeOf.isInstanceOf[Type.Reset]).map(_.name))
    // R18.5 — the ports that are unpacked arrays on the external module, by
    // name, so the emitter connects them whole instead of leaf by leaf.
    val wholePorts = ports.filter(p =>
      child.builder.signalIdForPort(p.name).exists(child.blackBoxUnpacked.contains)).map(_.name).toSet
    // R28 — the declared input-to-output combinational routes, by port name.
    // Empty is the common case and is an assumption the graph records.
    val portNameOf = ports.flatMap(p =>
      child.builder.signalIdForPort(p.name).map(_ -> p.name)).toMap
    val combPaths = child.blackBoxCombPaths.flatMap { case (from, to) =>
      for a <- portNameOf.get(from); b <- portNameOf.get(to) yield a -> b
    }
    _instances += Instance(instName, child.moduleName, outputConns.toList, src.loc,
      parameters = child.blackBoxParameters, external = true, wholePorts = wholePorts,
      outputs = ports.filter(_.direction == Direction.Out).map(_.name).toSet,
      combPaths = combPaths)
    // R18/R23 — inputs bind through the shared instance-connection mechanism.
    // An external declaration has no body to leave floating, so unlike a
    // generated child its inputs are not tracked as required connections.
    ports.filter(_.direction == Direction.In).foreach { p =>
      child.builder.signalIdForPort(p.name).foreach { childId =>
        child.builder.signalHandle(childId)
          .foreach(_.bindInstanceInput(this, instanceIndex, p.name))
        child.builder.bindAggregateInstanceInput(childId, this, instanceIndex, p.name)
      }
    }

  /** R13.2/R23 — Record a submodule instance and wire it.
    *
    * Clock and reset ports auto-connect from the enclosing domain (R13.2); an
    * explicit `u.clk := other` later replaces that connection rather than
    * adding a second one. Each data output becomes a parent-side net named
    * `<instance>_<port>` — R2's named exception, whose name derives from
    * instance and port with no counter — and the child's handle is rebound to
    * it, so reading `u.q` in the parent reads that net. Each data input is
    * bound so `u.p := expr` records a named connection on this instance
    * instead of an assignment (R23).
    *
    * The child has already elaborated its own body by the time this runs, so
    * rebinding its port handles cannot disturb statements it recorded against
    * its own ids. */
  def addInstance(child: scalahdl.elab.Module, childPorts: List[Port],
                  domain: ClockDomain, src: Source): Unit =
    val instName = resolveName(src, Type.Bool)  // name only; type is irrelevant
    checkNotInSimOnly(s"instance `$instName` is created",
      "instantiate it outside the block; a `simOnly` may read a child's outputs and may not " +
      "add one to the design", src.loc)
    children += child
    val instanceIndex = _instances.length
    val conns = mutable.ListBuffer.empty[(String, Expr)]
    val requiredInputs = mutable.ListBuffer.empty[String]
    // R13.6 — the nets this instance drives, and the pins that decide which
    // domain they are in.
    val outputNets = mutable.ListBuffer.empty[SignalId]
    val clockPorts = mutable.ListBuffer.empty[String]
    val resetPorts = mutable.ListBuffer.empty[String]

    // R13.2 — an input clock or reset is auto-connected from the enclosing
    // domain, and is *also* bound as an instance input so a later explicit
    // `u.clk := other` replaces that connection. That override is what R13.2
    // has always promised; binding it here is what makes it reachable.
    def bindInput(p: Port): Unit =
      child.builder.signalIdForPort(p.name).foreach { childId =>
        child.builder.signalHandle(childId)
          .foreach(_.bindInstanceInput(this, instanceIndex, p.name))
        // An aggregate's field leaves are bound once at construction, so
        // binding the root is not enough — they follow it here, or
        // `u.in.field := expr` would be an assignment rather than a connection.
        child.builder.bindAggregateInstanceInput(childId, this, instanceIndex, p.name)
      }

    childPorts.foreach { p =>
      p.typeOf match
        case Type.Clock if p.direction == Direction.In =>
          clockPorts += p.name
          conns += p.name -> Expr.Ref(domain.clkId)
          bindInput(p)
        case Type.Reset(_) if p.direction == Direction.In =>
          resetPorts += p.name
          domain.rstId.foreach(rid => conns += p.name -> Expr.Ref(rid))
          bindInput(p)
        case _ if p.direction == Direction.Out =>
          val parentNet = declareSignal(s"${instName}_${p.name}", p.typeOf,
            SignalKind.Wire, init = None, src.loc, domain)
          child.builder.signalIdForPort(p.name).foreach { childId =>
            child.builder.signalHandle(childId).foreach { handle =>
              handle.bindPath(parentNet.id, parentNet.name, false, Nil)
              handle.bindInstanceOutput(instName)
            }
            // An aggregate's field leaves are bound once at construction, so
            // rebinding the root is not enough — they follow it here, or
            // `u.out.field` in the parent would still carry the child's id.
            child.builder.rebindAggregate(childId, parentNet, Some(instName))
          }
          outputNets += parentNet.id
          conns += p.name -> parentNet.ref
        case _ =>
          requiredInputs += p.name
          bindInput(p)
    }

    instancePorts(instanceIndex) =
      InstancePorts(childPorts.map(_.name), requiredInputs.toList, child.moduleName, src.loc)
    instancePortTypes(instanceIndex) = childPorts.map(p => p.name -> p.typeOf).toMap
    instanceClocking(instanceIndex) = InstanceClocking(
      domain, outputNets.toList, clockPorts.toList, resetPorts.toList)
    // R5.6 — what the child claims about the clocks it is handed.
    val contracts = child.builder.relatedClockPortFamilies
    if contracts.nonEmpty then instanceRelatedClocks(instanceIndex) = contracts
    _instances += Instance(instName, child.moduleName, conns.toList, src.loc,
      outputs = childPorts.filter(_.direction == Direction.Out).map(_.name).toSet)

  /** R18/R23 — Record a named instance port connection. Last assignment wins,
    * which is how an explicit clock or reset connection overrides R13.2's
    * auto-connection. Connections are kept in the child's port declaration
    * order (R20.1), so the instantiation reads against the child's declaration
    * regardless of the order the parent wrote its assignments. */
  private[scalahdl] def connectInstance(instanceIndex: Int, portName: String,
                                        expr: Expr): Unit =
    val current = _instances(instanceIndex)
    checkConnectable(instanceIndex, portName)
    // A whole-port connection supersedes any leaf of the same port: last write
    // wins, and it wins over the whole port rather than over one pin of it.
    val superseded = portType(instanceIndex, portName)
      .map(t => leafPins(portName, t).toSet + portName)
      .getOrElse(Set(portName))
    val without = current.connections.filterNot(c => superseded.contains(c._1))
    _instances(instanceIndex) =
      current.copy(connections = insertByRank(instanceIndex, without, portName, expr))
    // R13.6 — a clock or reset connection is a statement about the instance's
    // domain, not only about a wire. Recomputed after every one of them, so
    // the order the two pins are written in does not matter.
    if instanceClocking.get(instanceIndex).exists(state =>
         state.clockPorts.contains(portName) || state.resetPorts.contains(portName))
    then retagInstanceDomain(instanceIndex)

  /** R23/R27 — the two things true of every connection, whole or by leaf: a
    * `simOnly` adds no wires, and a connection has no condition to be scoped
    * by. Accepting one inside a `when` silently would make the arm look
    * conditional while emitting an unconditional connection that also
    * overwrote whatever the sibling arms connected. */
  private def checkConnectable(instanceIndex: Int, portName: String): Unit =
    val current = _instances(instanceIndex)
    checkNotInSimOnly(s"port `$portName` of instance `${current.name}` is connected",
      "connect it outside the block; a connection is a wire and a `simOnly` adds none",
      current.loc)
    if scopeStack.nonEmpty then
      throw scalahdl.diag.ValidationFailed.one(scalahdl.diag.Diagnostic(
        severity = scalahdl.diag.Severity.Error,
        code = scalahdl.diag.Code.INSTANCE_CONNECT_IN_SCOPE,
        summary = s"port `$portName` of instance `${current.name}` is connected inside a conditional scope",
        primaryLoc = current.loc,
        related = List(scalahdl.diag.RelatedLoc(current.loc,
          s"`${current.name}` instantiated here")),
        fix = Some(s"drive a wire conditionally and connect it once, e.g. `val ${portName}Value = Wire(...)` assigned in the `when`, then `${current.name}.$portName := ${portName}Value` outside it")))

  /** R23 — a connection to **one leaf** of an aggregate input port.
    *
    * A bundle or vec port is one port in the source and one pin per leaf at the
    * boundary, so a leaf *is* a pin and connecting it is exactly what
    * `.p_field(expr)` spells. Nothing is invented: the expression lands on the
    * pin, the same way a scalar port's does.
    *
    * A port already connected whole is expanded into its leaves first, so the
    * two spellings compose — connect the port, then override one field — and
    * the leaves the override does not name keep what the whole connection gave
    * them. */
  private[scalahdl] def connectInstanceLeaf(instanceIndex: Int, portName: String,
                                            target: DslSignal, expr: Expr, loc: Loc): Unit =
    val current = _instances(instanceIndex)
    checkConnectable(instanceIndex, portName)
    val t = portType(instanceIndex, portName).getOrElse(
      throw IllegalArgumentException(
        s"port `$portName` of instance `${current.name}` has no recorded type"))
    require(!current.wholePorts.contains(portName),
      s"`$portName` of instance `${current.name}` is an unpacked-array port and connects " +
      s"whole, so it has no leaf to connect on its own")
    val pin = pinName(current.name, portName, t, target.path)
    // An earlier whole-port connection becomes its leaves, so this one replaces
    // a pin rather than the port.
    val expanded = current.connections.find(_._1 == portName) match
      case Some((_, whole)) =>
        val rest = current.connections.filterNot(_._1 == portName)
        expandAggregate(portName, t, whole).foldLeft(rest) {
          case (acc, (leaf, leafExpr)) => insertByRank(instanceIndex, acc, leaf, leafExpr)
        }
      case None => current.connections
    val without = expanded.filterNot(_._1 == pin)
    _instances(instanceIndex) =
      _instances(instanceIndex).copy(
        connections = insertByRank(instanceIndex, without, pin, expr))

  private def portType(instanceIndex: Int, portName: String): Option[Type] =
    instancePortTypes.get(instanceIndex).flatMap(_.get(portName))

  /** The pin one path into an aggregate port names, and the two paths that name
    * no pin at all. A window is not a pin — Verilog connects a whole port, not
    * a run of bits of one — and a runtime index is not one either, because a
    * port `Vec` is per-element and the element has to be known to be named. */
  private def pinName(instanceName: String, portName: String, portTypeOf: Type,
                      path: List[DslSignal.PathSeg]): String =
    path.foldLeft((portName, portTypeOf)) {
      case ((name, Type.Bundle(fields)), DslSignal.PathSeg.Field(field)) =>
        val fieldType = fields.find(_._1 == field).map(_._2).getOrElse(
          throw IllegalArgumentException(s"`$name` has no field `$field`"))
        (s"${name}_$field", fieldType)
      case ((name, Type.Vec(_, elem)), DslSignal.PathSeg.Index(Expr.Lit(index, _, _))) =>
        (s"${name}_$index", elem)
      case ((name, _), DslSignal.PathSeg.Slice(hi, lo)) =>
        throw IllegalArgumentException(
          s"`$instanceName.$name($hi, $lo)` is a window of a pin, and a connection names a " +
          "whole pin — drive a wire and connect that")
      case ((name, _), DslSignal.PathSeg.Index(_)) =>
        throw IllegalArgumentException(
          s"`$instanceName.$name` is indexed at run time, and a port Vec is one pin per " +
          "element — connect it with a constant index, or connect the port whole")
      case ((name, other), seg) =>
        throw IllegalArgumentException(
          s"`$instanceName.$name` is a $other and has no `$seg` to connect")
    }._1

  /** The pins one aggregate port flattens to, in the order the boundary spells
    * them — the same order and the same names the emitter produces (R07.1). */
  private def leafPins(pin: String, t: Type): List[String] = t match
    case Type.Bundle(fields) => fields.flatMap((f, ft) => leafPins(s"${pin}_$f", ft))
    case Type.Vec(n, elem)   => (0 until n).toList.flatMap(i => leafPins(s"${pin}_$i", elem))
    case _                   => List(pin)

  /** ...and the same expansion carrying the connected expression down with it,
    * so a whole-port connection becomes exactly the per-pin connections the
    * emitter would have written for it. */
  private def expandAggregate(pin: String, t: Type, expr: Expr): List[(String, Expr)] = t match
    case Type.Bundle(fields) =>
      fields.flatMap((f, ft) => expandAggregate(s"${pin}_$f", ft, Expr.Field(expr, f)))
    case Type.Vec(n, elem) =>
      (0 until n).toList.flatMap(i =>
        expandAggregate(s"${pin}_$i", elem, Expr.Index(expr, Expr.uint(BigInt(i), 32))))
    case _ => List(pin -> expr)

  /** Where a connection sits in the instantiation: its port's declaration
    * order, and within a port the leaf order above. A name with neither — a
    * blackbox connected before its ports were tracked — keeps its place. */
  private def insertByRank(instanceIndex: Int, connections: List[(String, Expr)],
                           name: String, expr: Expr): List[(String, Expr)] =
    instancePorts.get(instanceIndex) match
      case None => connections :+ (name -> expr)
      case Some(ports) =>
        val ranks = scala.collection.mutable.LinkedHashMap.empty[String, (Int, Int)]
        ports.declarationOrder.zipWithIndex.foreach { case (port, portRank) =>
          ranks(port) = (portRank, -1)
          portType(instanceIndex, port).foreach { t =>
            leafPins(port, t).zipWithIndex.foreach { case (leaf, leafRank) =>
              if !ranks.contains(leaf) then ranks(leaf) = (portRank, leafRank)
            }
          }
        }
        val last = (Int.MaxValue, Int.MaxValue)
        val rank = (n: String) => ranks.getOrElse(n, last)
        // A stable insertion by rank, not a re-sort: blackbox connections
        // recorded before their ports were tracked keep their relative order.
        val target = rank(name)
        val after = (other: (Int, Int)) =>
          other._1 > target._1 || (other._1 == target._1 && other._2 > target._2)
        val at = connections.indexWhere(c => after(rank(c._1)))
        if at < 0 then connections :+ (name -> expr)
        else connections.take(at) ++ ((name -> expr) :: connections.drop(at))

  /** R23 — Every data input of a generated child must be connected before the
    * parent freezes; Verilog would otherwise leave it floating. */
  private def unconnectedInstanceInputs: List[scalahdl.diag.Diagnostic] =
    instancePorts.toList.sortBy(_._1).flatMap { case (index, ports) =>
      val connected = _instances(index).connections.map(_._1).toSet
      val instance = _instances(index)
      // A port is connected whole, or every one of its pins is. An aggregate
      // with some pins connected and some not is the case worth naming: the
      // diagnostic says which pins are missing rather than reporting the whole
      // port as untouched, because the parent plainly meant to connect it.
      ports.requiredInputs.filterNot(connected).flatMap { port =>
        val pins = portType(index, port).map(leafPins(port, _)).getOrElse(List(port))
        val missing = pins.filterNot(connected)
        if missing.isEmpty then Nil
        else
          val partial = missing.length < pins.length
          val what =
            if pins.length == 1 then s"input `$port`"
            else if partial then s"${missing.length} of the ${pins.length} pins of input `$port` " +
                                 s"(${missing.mkString(", ")})"
            else s"input `$port`"
          List(scalahdl.diag.Diagnostic(
            severity = scalahdl.diag.Severity.Error,
            code = scalahdl.diag.Code.INSTANCE_INPUT_UNCONNECTED,
            summary = s"$what of instance `${instance.name}` (${ports.moduleName}) is not connected",
            primaryLoc = ports.loc,
            related = List(scalahdl.diag.RelatedLoc(ports.loc,
              s"`${instance.name}` instantiated here")),
            fix = Some(
              if partial then s"connect the rest, e.g. `${instance.name}.$port.<field> := <expr>`, " +
                              s"or connect the port whole with `${instance.name}.$port := <expr>`"
              else s"connect it, e.g. `${instance.name}.$port := <expr>`")))
      }
    }

  /** R15 — Declare an explicit memory. Ports are accumulated on a dedicated
    * builder and frozen into one immutable Memory IR node. */
  def addMemory(src: Source, kind: MemoryKind, depth: Int, elemType: Type,
                init: Option[MemoryInit]): MemoryBuilder =
    require(depth > 0, s"memory depth must be positive, got $depth")
    require(elemType.bitWidth > 0, s"memory element width must be positive, got $elemType")
    val name = resolveName(src, elemType)
    checkNotInSimOnly(s"memory `$name` is declared",
      "declare it outside the block; a `simOnly` may read a memory and may not create one",
      src.loc)
    val mem = new MemoryBuilder(name, kind, depth, elemType, init, src.loc)
    _memories += mem
    mem

  /** Allocate the named result of a memory read. Memory emission, rather than a
    * normal DriverBlock, drives this signal. */
  def addMemoryReadResult(name: String, t: Type, synchronous: Boolean,
                          domain: ClockDomain, loc: Loc): DslSignal =
    // Memory emission owns the optional synchronous packed read register. The
    // public result remains a wire (including aggregate leaf wires).
    declareSignal(name, t, SignalKind.Wire, init = None, loc, domain)

  /** `:=` — record an assignment at the current scope (R12/R22). Blocking for
    * combinational signals, non-blocking for registers.
    *
    * Inside a `when` the assignment goes into the frame's current arm, and
    * inside a `switch` alternative into that alternative; the enclosing
    * statement is what later lands on the signal's buffer, when the scope
    * closes. Outside every scope it is a bare statement. */
  def addAssign(target: DslSignal, expr: Expr, loc: Loc): Unit =
    // R25 — a state register is written by `goto` and by nothing else, which
    // is what makes the recorded transitions the whole story rather than the
    // part of it this construct happened to see. `goto` records its assignment
    // directly, so it does not pass through here.
    fsmRegisters.get(target.toLhs.rootId).foreach { (fsmName, fsmLoc) =>
      throw fsmFailure(scalahdl.diag.Code.FSM_STATE_ASSIGNED,
        s"`$fsmName` is a state register and is assigned outside its machine", loc,
        related = List(scalahdl.diag.RelatedLoc(fsmLoc, s"`$fsmName` declared here")),
        fix = "move the transition into the machine and write it as `goto(State.X)`")
    }
    // R27 — the rule that makes a `simOnly` removable.
    checkNotInSimOnly(s"`${target.name}` is assigned",
      "a `simOnly` block reads and reports; to act on the condition, write the assignment " +
      "outside the block", loc)
    val blocking = !target.isReg
    val stmt = Stmt.Assign(target.toLhs, expr, blocking, loc)
    val _ = recordStmt(target.toLhs.rootId, stmt)

  // ---- R27 simulation-only blocks ----

  /** True while a `simOnly` region is open. */
  private[scalahdl] def inSimOnly: Boolean = simOnlyRegions.nonEmpty

  /** R27 — refuse anything that would make a `simOnly` region part of the
    * circuit, naming what was attempted.
    *
    * This is the whole of the construct's safety and it is why it can be one
    * word: a region that cannot declare and cannot assign is a region the
    * emitter may wrap in `// synthesis translate_off` and a synthesiser may
    * delete, with the design that remains bit-identical to the one written. A
    * convention would not do — the divergence a stray assignment introduces is
    * between simulation and hardware, so simulation, the only thing that would
    * look, is exactly the thing that cannot see it. */
  private def refuseInSimOnly(what: String, fix: String, loc: Loc): Nothing =
    throw ValidationFailed.one(Diagnostic(
      severity = Severity.Error,
      code = Code.SIM_ONLY_DRIVES,
      summary = s"$what inside `simOnly`, which may not be part of the design",
      primaryLoc = loc,
      primaryNote = Some("written here"),
      related = simOnlyRegions.headOption.map(_._2).toList
        .map(RelatedLoc(_, "`simOnly` opened here")) ++
        List(RelatedLoc(Loc.NoLoc,
          "a `simOnly` block is emitted inside `// synthesis translate_off`, so a synthesiser " +
          "deletes it; a design with one has to be bit-identical to the same design without it")),
      fix = Some(fix)))

  /** R27 — the check refused at every point a statement or a declaration could
    * enter the design. Named for the thing being attempted, because that is
    * what the reader wrote. */
  private[scalahdl] def checkNotInSimOnly(what: String, fix: String, loc: Loc): Unit =
    if inSimOnly then refuseInSimOnly(what, fix, loc)

  /** Open a `simOnly` region on `domain`, allocating that domain's check block
    * if this is the first region to name it.
    *
    * The block's id is a signal id that names no signal: `stmts` is keyed by
    * target and R12's projection sorts on the same key, so giving the block an
    * id is what lets a `when` closing around a `simOnly` copy its condition
    * onto the checks exactly as it does onto an assignment. Nothing declares
    * the id, so nothing emits it. */
  def beginSimOnly(domain: ClockDomain, loc: Loc): Unit =
    val _ = checkBlocks.getOrElseUpdate(domain, {
      val fresh = nextId()
      stmts(fresh) = mutable.ListBuffer.empty
      domains(fresh) = domain
      (fresh, loc)
    })
    simOnlyRegions.prepend((domain, loc))

  def endSimOnly(): Unit =
    if simOnlyRegions.isEmpty then
      throw ElabError("`simOnly` closed with no open region (library defect)", Loc.NoLoc)
    val _ = simOnlyRegions.remove(0)

  /** R27 — record an immediate assertion into the innermost open scope, under
    * the check block of the domain the enclosing `simOnly` named.
    *
    * It goes through `recordStmt` like every other statement, which is what
    * gives it the `when` nest and the `switch` arm it was written under for
    * free — and what ends a preceding `when` chain's reopen window, since a
    * check between a `when` and its `otherwise` separates the two exactly as
    * an assignment does (R12). */
  def addCheck(cond: Expr, message: String, loc: Loc): Unit =
    val id = simOnlyRegions.headOption match
      case Some((domain, _)) =>
        checkBlocks.getOrElse(domain,
          throw ElabError("`simOnly` has no check block (library defect)", loc))._1
      case None =>
        throw ValidationFailed.one(Diagnostic(
          severity = Severity.Error,
          code = Code.CHECK_OUTSIDE_SIM_ONLY,
          summary = "`check` is a simulation-only statement and is written outside `simOnly`",
          primaryLoc = loc,
          primaryNote = Some("written here"),
          related = List(RelatedLoc(Loc.NoLoc,
            "the guard that keeps a check out of the hardware belongs to the block, not to the " +
            "statement: one `simOnly` wraps every check it holds in one `translate_off` region")),
          fix = Some("wrap it: `simOnly { check(cond, \"message\") }`")))
    val _ = recordStmt(id, Stmt.Check(id, cond, message, loc))

  // ---- when / elsewhen / otherwise (R12 condition scoping) ----

  /** Open a fresh `when(cond)` chain. Subsequent `:=` land in its then-arm. */
  def beginWhen(cond: Expr, loc: Loc): Unit =
    lastChain = None
    chainSeq += 1
    openChains.prepend(OpenChain(chainSeq, scopeStack.length))
    scopeStack.prepend(CondFrame(cond, loc))

  /** Continue chain `id` with an `elsewhen`: reopen the chain, switch its
    * innermost frame to the else arm, and nest the new condition inside it.
    * `else if` is an `else` that contains an `if` — that nesting is what R11
    * reads as an exhaustive-by-construction chain. */
  def beginElseWhen(id: Int, cond: Expr, loc: Loc): Unit =
    reopenChain(id)
    scopeStack.prepend(CondFrame(cond, loc))

  /** Continue chain `id` with `otherwise`: reopen it and collect the body into
    * the innermost frame's else arm. */
  def beginOtherwise(id: Int): Unit = reopenChain(id)

  /** Close every frame the open chain pushed, innermost first, recording one
    * target-specific `Stmt.When` into each signal the frame wrote. Driver
    * blocks are per signal, so assignments to a different target are removed
    * recursively before the conditional is copied into that target's block.
    *
    * Returns the chain id, which the `WhenBlock` hands back to continue
    * exactly this chain — a `WhenBlock` stashed in a `val` and used after
    * other statements have been recorded can no longer reopen. */
  def endWhen(): Int =
    if openChains.isEmpty then
      throw ElabError("`when` closed with no open chain (library defect)", Loc.NoLoc)
    val open = openChains.remove(0)
    val links = mutable.ListBuffer.empty[ChainLink]
    while scopeStack.length > open.base do
      scopeStack.remove(0) match
        case frame: CondFrame => links.prepend(ChainLink(frame, closeCondFrame(frame)))
        case other: Scope =>
          throw ElabError("a `when` chain closed across a `switch` scope (library defect)",
            Loc.NoLoc)
    lastChain = Some(ClosedChain(open.id, open.base, links.toList))
    open.id

  /** Record one `Stmt.When` per signal the frame wrote, and report the buffers
    * they landed in so a reopening `elsewhen`/`otherwise` can take them back
    * out again. */
  private def closeCondFrame(frame: CondFrame): List[mutable.ListBuffer[Stmt]] =
    frame.touched.toSeq.sorted(Ordering.by[SignalId, Int](_.value)).map { sid =>
      val thenStmts = frame.thenArm.toList.flatMap(filterStmtFor(_, sid))
      val elseStmts = frame.elseArm.toList.flatMap(filterStmtFor(_, sid))
      recordStmt(sid, Stmt.When(frame.cond, thenStmts, elseStmts, frame.loc))
    }.toList

  /** Undo a chain's close and push its frames back, outermost first, so the
    * head is the innermost frame again. Each frame keeps the arms it already
    * had; only the statements the close *emitted* are withdrawn, and they are
    * always at the tail of the buffers they went into, because a reopen can
    * only directly follow the close. */
  private def reopenChain(id: Int): Unit =
    val closed = lastChain.filter(_.id == id).getOrElse(
      throw ElabError(
        "`elsewhen`/`otherwise` must follow its `when` directly — the chain was " +
        "closed by an intervening statement", Loc.NoLoc))
    closed.links.foreach { link =>
      link.undo.foreach(buf => buf.remove(buf.length - 1))
      scopeStack.prepend(link.frame)
    }
    closed.links.lastOption.foreach(_.frame.inElse = true)
    lastChain = None
    openChains.prepend(OpenChain(closed.id, closed.base))

  // ---- switch / is / default (R22 multi-way scoping) ----

  /** Open a `switch(selector)` scope. Alternatives are collected by `is` and
    * `default` while it is open; nothing is recorded until it closes, so
    * duplicate detection sees the whole body however it was produced. */
  def pushSwitch(selector: Expr, loc: Loc): Unit =
    lastChain = None
    scopeStack.prepend(SwitchFrame(SelectKind.Values, selector, loc))

  /** R26 — open a `priorityCase`/`parallelCase` scope. It is an R22 switch on
    * the constant one: the arms carry the conditions, which is what Verilog's
    * `case (1'b1)` says and what lets every mechanism written against `Switch`
    * read this without knowing it is one. */
  def pushSelect(kind: SelectKind, loc: Loc): Unit =
    lastChain = None
    val one = Expr.Lit(BigInt(1), Some(1), Some(LitKind.Bool))
    scopeStack.prepend(SwitchFrame(kind, one, loc))

  /** Open an `is(values...)` alternative in the innermost switch. */
  def beginCase(values: List[Expr], loc: Loc): Unit =
    val frame = openSwitchFrame("is", loc)
    if frame.kind.onConditions then
      throw switchFailure(scalahdl.diag.Code.SWITCH_ARM_CONTEXT,
        s"`is` names a value, and ${selectName(frame.kind)} selects on conditions", loc,
        related = List(scalahdl.diag.RelatedLoc(frame.loc, s"in this `${selectName(frame.kind)}`")),
        fix = "write the condition as `on(cond) { ... }`; `is` belongs to a `switch` over a selector")
    if values.isEmpty then
      throw switchFailure(scalahdl.diag.Code.SWITCH_EMPTY_ARM,
        "`is()` names no value, so it selects nothing", loc,
        related = List(scalahdl.diag.RelatedLoc(frame.loc, "in this `switch`")),
        fix = "give the alternative at least one constant value, e.g. `is(0.U(2)) { ... }`")
    frame.open = Some(OpenArm(Some(values), loc))

  /** R26 — open an `on(conditions...)` alternative. Shares `endCase`: an arm is
    * an arm once its labels are recorded, whatever selected it. */
  def beginSelectArm(conditions: List[Expr], loc: Loc): Unit =
    val frame = openSwitchFrame("on", loc)
    if !frame.kind.onConditions then
      throw switchFailure(scalahdl.diag.Code.SWITCH_ARM_CONTEXT,
        "`on` names a condition, and a `switch` selects on values", loc,
        related = List(scalahdl.diag.RelatedLoc(frame.loc, "in this `switch`")),
        fix = "write the value as `is(v) { ... }`, or open a `priorityCase`/`parallelCase` if the alternatives are conditions")
    if conditions.isEmpty then
      throw switchFailure(scalahdl.diag.Code.SWITCH_EMPTY_ARM,
        "`on()` names no condition, so it selects nothing", loc,
        related = List(scalahdl.diag.RelatedLoc(frame.loc, s"in this `${selectName(frame.kind)}`")),
        fix = "give the alternative at least one one-bit condition, e.g. `on(a === b) { ... }`")
    frame.open = Some(OpenArm(Some(conditions), loc))

  /** R15.5 — the condition a statement written *here* runs under, as one
    * expression, outermost term first.
    *
    * Assignments never need this: they are recorded into a driver block that
    * carries the `When`/`Switch` nest itself, so R12's structure is the
    * condition. A **memory write** is not a statement in a driver block — a
    * write port is address, data and an enable — so the nest around it had
    * nowhere to go, and a `mem.write` inside a `when` used to emit a write on
    * every clock. This is what it goes into instead.
    *
    * The `default` arm is the one scope whose condition is not knowable while
    * it is open: it means *every value no `is` names*, and R22 lets an `is`
    * appear after it. So it is refused rather than guessed, which is the
    * loudest thing this can honestly do.
    */
  def scopeCondition(what: String, loc: Loc,
                     code: scalahdl.diag.Code =
                       scalahdl.diag.Code.MEM_WRITE_IN_DEFAULT_ARM): Option[Expr] =
    val terms = scopeStack.toList.reverse.map {
      case frame: CondFrame =>
        if frame.inElse then Expr.UnOp("!", frame.cond) else frame.cond
      case frame: SwitchFrame => frame.open match
        case Some(arm) => arm.values match
          case Some(values) =>
            // A `switch` compares its selector against each label; `on` names
            // conditions already, and R26 makes the selector the constant one.
            val armTerms =
              if frame.kind.onConditions then values
              else values.map(v => Expr.BinOp("==", frame.selector, v))
            armTerms.reduce((a, b) => Expr.BinOp("|", a, b))
          case None =>
            throw scalahdl.diag.ValidationFailed.one(scalahdl.diag.Diagnostic(
              severity = scalahdl.diag.Severity.Error,
              code = code,
              summary =
                s"a $what in a `default` arm has no condition yet — `default` " +
                "means every value no `is` names, and an `is` may be added " +
                "after it",
              primaryLoc = loc,
              related = List(scalahdl.diag.RelatedLoc(frame.loc, "in this switch")),
              fix = Some(
                "name the values in an `is(...)` arm, or pass the condition as " +
                s"the $what's `enable` argument")))
        case None => Expr.uint(BigInt(1), 1)   // between arms: no condition
    }
    terms.reduceOption((a, b) => Expr.BinOp("&", a, b))

  /** What the source wrote, for a diagnostic to name it. */
  private def selectName(kind: SelectKind): String = kind match
    case SelectKind.Values   => "switch"
    case SelectKind.Priority => "priorityCase"
    case SelectKind.OneHot   => "parallelCase"

  /** Close the open `is` alternative, appending it in elaboration order. */
  def endCase(): Unit =
    val frame = currentSwitchFrame("is")
    frame.open match
      case Some(arm) if arm.values.isDefined =>
        frame.cases += SwitchCase(arm.values.get, arm.stmts.toList, arm.loc)
        frame.open = None
        lastChain = None
      case _ =>
        throw ElabError("`is` closed with no open alternative (library defect)", Loc.NoLoc)

  /** Open the `default` alternative; at most one per switch (R22). */
  def beginDefault(loc: Loc): Unit =
    val frame = openSwitchFrame("default", loc)
    frame.defaultArm.foreach { first =>
      throw switchFailure(scalahdl.diag.Code.SWITCH_DUPLICATE_DEFAULT,
        "a `switch` may have only one `default`", loc,
        related = List(scalahdl.diag.RelatedLoc(first.loc, "first `default` here"),
                       scalahdl.diag.RelatedLoc(frame.loc, "in this `switch`")),
        fix = "merge the two `default` bodies into one")
    }
    frame.open = Some(OpenArm(None, loc))
    // R25 — inside a machine this is its default arm, and a `goto` written
    // here is an edge from the values no arm names.
    noteFsmDefault(open = true)

  /** Close the `default` alternative. */
  def endDefault(): Unit =
    val frame = currentSwitchFrame("default")
    noteFsmDefault(open = false)
    frame.open match
      case Some(arm) if arm.values.isEmpty =>
        frame.defaultArm = Some(SwitchCase(Nil, arm.stmts.toList, arm.loc))
        frame.open = None
        lastChain = None
      case _ =>
        throw ElabError("`default` closed with no open alternative (library defect)", Loc.NoLoc)

  /** Close the switch and record one target-specific `Stmt.Switch` into each
    * signal it wrote.
    *
    * Every alternative is retained, including ones this target never writes.
    * Dropping them would make the projection lie to R11: in
    * `switch(s) { is(0) { x := a }; is(1) { y := b }; default { x := c } }`
    * the arm for `1` leaves `x` unassigned, and an `x` projection that had
    * only `is(0)` and the default would look fully covered. An arm that says
    * nothing about this signal is therefore kept, with an empty body. */
  def popSwitch(): Unit =
    val frame = scopeStack.remove(0) match
      case sf: SwitchFrame => sf
      case _ => throw ElabError("`switch` closed with no open switch (library defect)", Loc.NoLoc)
    lastChain = None
    frame.touched.toSeq.sorted(Ordering.by[SignalId, Int](_.value)).foreach { sid =>
      val cases = frame.cases.toList.map { c =>
        SwitchCase(c.values, c.stmts.flatMap(filterStmtFor(_, sid)), c.loc)
      }
      val defaults = frame.defaultArm.map(_.stmts.flatMap(filterStmtFor(_, sid)))
      val _ = recordStmt(sid, Stmt.Switch(frame.kind, frame.selector, cases, defaults, frame.loc))
    }

  /** The innermost scope as a switch frame with no alternative already open —
    * the only context in which `is` and `default` are legal (R22). */
  private def openSwitchFrame(what: String, loc: Loc): SwitchFrame =
    scopeStack.headOption match
      case Some(frame: SwitchFrame) if frame.open.isEmpty => frame
      case Some(frame: SwitchFrame) =>
        throw switchFailure(scalahdl.diag.Code.SWITCH_ARM_CONTEXT,
          s"`$what` appears inside another `switch` alternative", loc,
          related = List(scalahdl.diag.RelatedLoc(frame.open.get.loc, "alternative opened here")),
          fix = "close the alternative first; alternatives do not nest, though a nested `switch` may open inside one")
      case _ =>
        throw switchFailure(scalahdl.diag.Code.SWITCH_ARM_CONTEXT,
          s"`$what` is legal only directly inside a `switch` body", loc,
          related = Nil,
          fix = s"wrap it in `switch(selector) { $what(...) { ... } }`; a helper or a Scala loop may call it, as long as a `switch` is open")

  private def currentSwitchFrame(what: String): SwitchFrame =
    scopeStack.headOption match
      case Some(frame: SwitchFrame) => frame
      case _ => throw ElabError(s"`$what` closed outside its switch (library defect)", Loc.NoLoc)

  private def switchFailure(code: scalahdl.diag.Code, summary: String, loc: Loc,
                            related: List[scalahdl.diag.RelatedLoc],
                            fix: String): scalahdl.diag.ValidationFailed =
    scalahdl.diag.ValidationFailed(List(scalahdl.diag.Diagnostic(
      severity = scalahdl.diag.Severity.Error,
      code = code,
      summary = summary,
      primaryLoc = loc,
      related = related.filter(_.loc != Loc.NoLoc),
      fix = Some(fix))))

  // ---- R25 state machines ----

  /** The machines declared in this module, in declaration order. Metadata: an
    * `Fsm` elaborates to the R22 switch already in the driver blocks, and this
    * describes it for the report and for the checks below. */
  private val fsms = mutable.ListBuffer.empty[FsmInfo]

  /** The machine currently elaborating, if any. A list rather than an option
    * only so that "a machine inside a machine" is a diagnosable state. */
  private val openFsms = mutable.ListBuffer.empty[OpenFsm]

  /** Every state register an `Fsm` declared, with the machine's name and
    * location, so `:=` can refuse to write one from outside its machine. R25's
    * checks are worth what the closure of the transition set is worth. */
  private val fsmRegisters = mutable.Map.empty[SignalId, (String, Loc)]

  def fsmList: List[FsmInfo] = fsms.toList

  /** R25 — open a machine: declare the state register, open an R22 switch on
    * it, and start collecting states. Returns the register, which is what the
    * `val` binds and every other use site reads. */
  def beginFsm(src: Source, encoding: scalahdl.dsl.Encoding,
               init: Option[scalahdl.dsl.EncodingValue],
               powerUp: Option[scalahdl.dsl.EncodingValue],
               domain: ClockDomain): DslSignal =
    openFsms.headOption.foreach { outer =>
      throw fsmFailure(scalahdl.diag.Code.FSM_SCOPE_CONTEXT,
        s"a state machine is declared inside `${outer.name}`", src.loc,
        related = List(scalahdl.diag.RelatedLoc(outer.loc, s"`${outer.name}` opened here")),
        fix = "close the enclosing machine first; machines do not nest")
    }
    // R13.4 — the machine starts from its reset state, its power-up state, or
    // both; with neither there is no start and nothing to reach states from.
    if init.isEmpty && powerUp.isEmpty then
      throw fsmFailure(scalahdl.diag.Code.FSM_NO_INITIAL_STATE,
        s"state machine `${src.name.getOrElse("?")}` has neither `init` nor `powerUp`", src.loc,
        related = Nil,
        fix = "give `init` for the state a reset loads, `powerUp` for the state a " +
          "bitstream loads, or both")
    List("init" -> init, "powerUp" -> powerUp).foreach {
      case (argument, Some(state)) if state.encoding ne encoding =>
        throw fsmFailure(scalahdl.diag.Code.FSM_FOREIGN_STATE,
          s"initial state `$state` is not a member of `${encoding.name}`", src.loc,
          related = Nil,
          fix = s"give `$argument` a member of `${encoding.name}`, the machine's own encoding")
      case _ => ()
    }
    val reg = addReg(src, encoding.toType, init.map(_.expr), domain, Some(encoding.info),
                     powerUp.map(_.expr))
    val frame = new SwitchFrame(SelectKind.Values, reg.ref, src.loc)
    lastChain = None
    scopeStack.prepend(frame)
    fsmRegisters(reg.id) = (reg.name, src.loc)
    openFsms.prepend(new OpenFsm(reg.name, reg, encoding, frame,
      init.map(v => BigInt(v.code)), powerUp.map(v => BigInt(v.code)), src.loc))
    reg

  /** R25 / R25.1 — `in(s) { ... }` and `in(s1, s2, ...) { ... }`: one arm, an
    * R22 `is(s1, s2, ...)` on the machine's switch.
    *
    * Several states sharing one body is R22's alias list read as a graph: the
    * arm is one, and every state that selects it is a `from` of every `goto`
    * inside it. So each named state is enlisted separately — it has its own
    * `FsmState`, and the no-exit and reachability checks see it as its own
    * state — while the case item carries all of their labels, which is exactly
    * what the source said. */
  def beginFsmState(states: List[scalahdl.dsl.EncodingValue], loc: Loc): Unit =
    val fsm = currentFsm("in", loc)
    if states.isEmpty then
      throw fsmFailure(scalahdl.diag.Code.SWITCH_EMPTY_ARM,
        s"`in()` names no state of `${fsm.name}`, so it selects nothing", loc,
        related = List(scalahdl.diag.RelatedLoc(fsm.loc, s"`${fsm.name}` opened here")),
        fix = s"name at least one state, e.g. `in(${fsm.encoding.name}.First) { ... }`")
    states.foreach { state =>
      checkStateMember(fsm, state, "in", loc)
      // Checked as each is enlisted rather than against the machine as it was,
      // so `in(A, A)` is caught by the same rule that catches two arms for `A`.
      fsm.states.find(_.code == BigInt(state.code)).foreach { first =>
        throw fsmFailure(scalahdl.diag.Code.FSM_DUPLICATE_STATE,
          s"state `${state}` has two arms in `${fsm.name}`", loc,
          related = List(scalahdl.diag.RelatedLoc(first.loc, s"`${first.name}` already declared here")),
          fix = "merge the two bodies into one arm, or name both states in one " +
            "`in(a, b) { ... }` if they share it")
      }
      fsm.states += FsmState(BigInt(state.code), state.toString, terminal = false, loc)
    }
    fsm.openArm = Some(states.map(state => BigInt(state.code)))
    beginCase(states.map(_.expr), loc)

  def endFsmState(): Unit =
    val fsm = currentFsm("in", Loc.NoLoc)
    endCase()
    fsm.openArm = None

  /** R25.1 — the machine's own state register, read from inside its body.
    *
    * `val state = Fsm(...) { ... state ... }` is a recursive value in Scala, so
    * the register the machine *returns* is not in scope while the body that
    * produces it elaborates. An arm naming several states has to be able to
    * tell them apart, so the register is looked up on the builder here, the
    * same way `goto` and `in` are. It is the register and not a copy of it —
    * `addAssign`'s R25 guard therefore refuses a write to it as it always
    * has. */
  def fsmState(loc: Loc): DslSignal = currentFsm("currentState", loc).reg

  /** `terminal(s)` — a state with no exit, declared rather than inferred from
    * an arm that happens to contain no `goto`. Emits the same empty arm. */
  def fsmTerminal(state: scalahdl.dsl.EncodingValue, loc: Loc): Unit =
    val fsm = currentFsm("terminal", loc)
    checkStateMember(fsm, state, "terminal", loc)
    fsm.states.find(_.code == BigInt(state.code)).foreach { first =>
      throw fsmFailure(scalahdl.diag.Code.FSM_DUPLICATE_STATE,
        s"state `${state}` has two arms in `${fsm.name}`", loc,
        related = List(scalahdl.diag.RelatedLoc(first.loc, s"`${first.name}` already declared here")),
        fix = "declare the state once, as `in` or as `terminal`")
    }
    fsm.states += FsmState(BigInt(state.code), state.toString, terminal = true, loc)
    beginCase(List(state.expr), loc)
    endCase()

  /** `goto(s)` — assign the state register and record the edge, guarded by the
    * `when` scopes open between here and the machine's switch. */
  def fsmGoto(state: scalahdl.dsl.EncodingValue, loc: Loc): Unit =
    val fsm = currentFsm("goto", loc)
    checkStateMember(fsm, state, "goto", loc)
    val from = fsm.openArm.getOrElse {
      throw fsmFailure(scalahdl.diag.Code.FSM_SCOPE_CONTEXT,
        s"`goto` is outside every arm of `${fsm.name}`", loc,
        related = List(scalahdl.diag.RelatedLoc(fsm.loc, s"`${fsm.name}` opened here")),
        fix = "put the transition inside an `in(state) { ... }` arm or the `default` arm")
    }
    val to = BigInt(state.code)
    val guard = currentGuard(fsm.frame)
    // R25.1 — an arm naming several states is several edges. The `default`
    // arm names none, and its one edge is `from = None`: the values no arm
    // lists, which have no code to be recorded under.
    if from.isEmpty then
      fsm.transitions += FsmTransition(None, to, guard, selfLoop = false, loc)
    else
      from.foreach { code =>
        fsm.transitions += FsmTransition(Some(code), to, guard, selfLoop = code == to, loc)
      }
    val stmt = Stmt.Assign(fsm.reg.toLhs, state.expr, blocking = false, loc)
    val _ = recordStmt(fsm.reg.id, stmt)

  /** `stay()` — this arm deliberately holds. R11.1 already holds a register
    * with no assignment, so nothing is emitted; what is recorded is that the
    * author said so. */
  def fsmStay(loc: Loc): Unit =
    val fsm = currentFsm("stay", loc)
    val from = fsm.openArm.getOrElse {
      throw fsmFailure(scalahdl.diag.Code.FSM_SCOPE_CONTEXT,
        s"`stay` is outside every arm of `${fsm.name}`", loc,
        related = List(scalahdl.diag.RelatedLoc(fsm.loc, s"`${fsm.name}` opened here")),
        fix = "put it inside an `in(state) { ... }` arm")
    }
    from.foreach { code =>
      fsm.transitions += FsmTransition(Some(code), code, currentGuard(fsm.frame),
        selfLoop = true, loc)
    }

  /** Close the machine: pop its switch (which records the projected `Stmt.
    * Switch` on every signal the body wrote, exactly as R22 does), then decide
    * the three graph properties, which is the first moment the whole graph
    * exists. Every failing property is reported together. */
  def endFsm(): DslSignal =
    val fsm = currentFsm("Fsm", Loc.NoLoc)
    popSwitch()
    openFsms.remove(0)

    val diagnostics = mutable.ListBuffer.empty[scalahdl.diag.Diagnostic]
    val declared = fsm.states.map(_.code).toSet

    // Every member of the encoding is a state the machine can hold, so one
    // with no arm is a state the author has said nothing about.
    fsm.info.members.filterNot((code, _) => declared.contains(code)).foreach {
      case (code, member) =>
        diagnostics += scalahdl.diag.Diagnostic(
          severity = scalahdl.diag.Severity.Error,
          code = scalahdl.diag.Code.FSM_STATE_UNHANDLED,
          summary = s"`${fsm.info.name}.$member` has no arm in `${fsm.name}`",
          primaryLoc = fsm.loc,
          related = Nil,
          fix = Some(s"add `in(${fsm.info.name}.$member) { ... }`, " +
            s"or `terminal(${fsm.info.name}.$member)` if it has no exit"))
    }

    // A non-terminal state with no way out. A conditional `goto` counts: the
    // question is whether an exit is written at all, not whether it fires.
    fsm.states.filterNot(_.terminal).foreach { state =>
      val exits = fsm.transitions.exists(t => t.from.contains(state.code) && t.to != state.code)
      if !exits then
        diagnostics += scalahdl.diag.Diagnostic(
          severity = scalahdl.diag.Severity.Error,
          code = scalahdl.diag.Code.FSM_STATE_NO_EXIT,
          summary = s"state `${state.name}` of `${fsm.name}` has no way out",
          primaryLoc = state.loc,
          related = Nil,
          fix = Some(s"add a `goto` to another state, or declare it " +
            s"`terminal(${state.name})` if it is meant to hold until reset"))
    }

    // Reachability from the initial state, transitively, so a group of states
    // that only reach each other is caught as well. A transition written in
    // the `default` arm is taken from the values no arm names — the machine
    // can hold one, which is why the arm exists — so its target is reachable.
    val edges = fsm.transitions.groupBy(_.from)
    val reachable = mutable.Set.empty[BigInt]
    val queue = mutable.Queue.empty[BigInt]
    def discover(code: BigInt): Unit =
      if reachable.add(code) then queue.enqueue(code)
    fsm.entryCodes.foreach(discover)
    edges.getOrElse(None, Nil).foreach(t => discover(t.to))
    while queue.nonEmpty do
      val code = queue.dequeue()
      edges.getOrElse(Some(code), Nil).foreach(t => discover(t.to))
    fsm.states.filterNot(s => reachable.contains(s.code)).foreach { state =>
      diagnostics += scalahdl.diag.Diagnostic(
        severity = scalahdl.diag.Severity.Error,
        code = scalahdl.diag.Code.FSM_STATE_UNREACHABLE,
        summary = s"state `${state.name}` of `${fsm.name}` is unreachable from " +
          fsm.entryCodes.map(c => s"`${fsm.stateNameOf(c)}`").mkString(" or ") + "",
        primaryLoc = state.loc,
        related = List(scalahdl.diag.RelatedLoc(fsm.loc, s"`${fsm.name}` starts here")),
        fix = Some(s"add a `goto(${state.name})` from a reachable state, or remove the arm"))
    }

    if diagnostics.nonEmpty then throw scalahdl.diag.ValidationFailed(diagnostics.result())

    fsms += FsmInfo(fsm.name, fsm.reg.id, fsm.info, fsm.initCode, fsm.powerUpCode,
      fsm.states.toList, fsm.transitions.toList, fsm.loc)
    fsm.reg

  /** R22's `default` inside a machine: the arm is R22's own, and this is what
    * lets a `goto` written in it record an edge from the unlisted values. */
  private def noteFsmDefault(open: Boolean): Unit =
    openFsms.headOption.foreach { fsm =>
      if scopeStack.headOption.exists(_ eq fsm.frame) then
        fsm.openArm = if open then Some(Nil) else None
    }

  /** The conjunction of the `when` conditions open between here and the
    * machine's switch, negated where the scope is an `otherwise` arm — the
    * same structure that produces the hardware, read rather than restated. */
  private def currentGuard(until: Scope): Option[Expr] =
    val terms = scopeStack.iterator
      .takeWhile(_ ne until)
      .collect {
        case frame: CondFrame => if frame.inElse then Expr.UnOp("!", frame.cond) else frame.cond
      }
      .toList
      .reverse
    terms.reduceOption((outer, inner) => Expr.BinOp("&", outer, inner))

  private def currentFsm(what: String, loc: Loc): OpenFsm =
    openFsms.headOption.getOrElse {
      throw fsmFailure(scalahdl.diag.Code.FSM_SCOPE_CONTEXT,
        s"`$what` is legal only inside an `Fsm` body", loc,
        related = Nil,
        fix = s"wrap it in `Fsm(States, init = States.First) { $what ... }`; a helper method " +
          "or a Scala loop may call it, as long as a machine is open")
    }

  private def checkStateMember(fsm: OpenFsm, state: scalahdl.dsl.EncodingValue,
                               what: String, loc: Loc): Unit =
    if state.encoding ne fsm.encoding then
      throw fsmFailure(scalahdl.diag.Code.FSM_FOREIGN_STATE,
        s"`$what` names `$state`, which is not a state of `${fsm.name}` " +
          s"(encoding `${fsm.encoding.name}`)", loc,
        related = List(scalahdl.diag.RelatedLoc(fsm.loc, s"`${fsm.name}` declared here")),
        fix = s"name a member of `${fsm.encoding.name}`")

  private def fsmFailure(code: scalahdl.diag.Code, summary: String, loc: Loc,
                         related: List[scalahdl.diag.RelatedLoc],
                         fix: String): scalahdl.diag.ValidationFailed =
    scalahdl.diag.ValidationFailed(List(scalahdl.diag.Diagnostic(
      severity = scalahdl.diag.Severity.Error,
      code = code,
      summary = summary,
      primaryLoc = loc,
      related = related.filter(_.loc != Loc.NoLoc),
      fix = Some(fix))))

  private def filterStmtFor(stmt: Stmt, target: SignalId): Option[Stmt] = stmt match
    case assign @ Stmt.Assign(lhs, _, _, _) =>
      Option.when(lhs.rootId == target)(assign)
    case write @ Stmt.MemWrite(id, _, _, _) =>
      Option.when(id == target)(write)
    // R27 — a check names the block it belongs to, so the projection sorts it
    // like any other statement and a `when` around it reaches the check block
    // and no driver block.
    case check @ Stmt.Check(block, _, _, _) =>
      Option.when(block == target)(check)
    case Stmt.When(cond, thenStmts, elseStmts, loc) =>
      val filteredThen = thenStmts.flatMap(filterStmtFor(_, target))
      val filteredElse = elseStmts.flatMap(filterStmtFor(_, target))
      Option.when(filteredThen.nonEmpty || filteredElse.nonEmpty)(
        Stmt.When(cond, filteredThen, filteredElse, loc))
    case Stmt.Switch(skind, selector, cases, defaults, loc) =>
      // R22 — the alternative list is structure, not content: it is projected
      // whole (see `popSwitch`), and an absent default stays absent while a
      // present one stays present even if this target is written in neither.
      val filteredCases = cases.map(c => c.copy(stmts = c.stmts.flatMap(filterStmtFor(_, target))))
      val filteredDefaults = defaults.map(_.flatMap(filterStmtFor(_, target)))
      Option.when(filteredCases.exists(_.stmts.nonEmpty) || filteredDefaults.exists(_.nonEmpty))(
        Stmt.Switch(skind, selector, filteredCases, filteredDefaults, loc))

  // ---- freeze to IR (R17) ----

  def freeze(name: String, loc: Loc): Module =
    // R6.1 — resolve `None`-width literals against their context before
    // freezing. A literal in a BinOp inherits the other operand's width; a
    // literal with no context (bare assignment target, two-literal BinOp)
    // is an error. This is elaboration, not a backend rewrite — the IR after
    // freeze is fully resolved and the emitter sees only `Some(w)` literals.
    val litErrs = mutable.ListBuffer.empty[scalahdl.diag.Diagnostic]
    // R23 — a floating child input is decided here, once every `:=` that could
    // have connected one has run.
    litErrs ++= unconnectedInstanceInputs
    // R5.6 — and so is a child's clock contract, for the same reason.
    litErrs ++= brokenClockContracts
    // R6.3 — a register's `init` is an assignment boundary like any other, and
    // the target width is the register's own declared type.
    val resolvedSignals = _signals.map { s =>
      // R13.8 — and so is its `powerUp`, the same target by the other event.
      s.copy(init = s.init.map(e =>
               resolveInContext(e, Some(s.typeOf), LitCtx(s"`${s.name}`", s.loc, s.loc), litErrs)),
             powerUp = s.powerUp.map(e =>
               resolveInContext(e, Some(s.typeOf), LitCtx(s"`${s.name}`", s.loc, s.loc), litErrs)))
    }.toList
    val resolvedDrivers = _signals.collect {
      case s if stmts.get(s.id).exists(_.nonEmpty) =>
        val resolved = stmts(s.id).map(resolveStmt(_, litErrs)).toList
        DriverBlock(s.id, domains(s.id), clocked = s.isReg, resolved)
      case s if s.isReg && s.init.isDefined && stmts.get(s.id).forall(_.isEmpty) =>
        DriverBlock(s.id, domains(s.id), clocked = true, Nil)
    }.toList
    if litErrs.nonEmpty then
      throw scalahdl.diag.ValidationFailed(litErrs.result())
    val resolvedMemories = _memories.map(m =>
      m.freeze((e, at) => resolveExpr(e, LitCtx(s"`${m.name}`", at), litErrs))).toList
    // R6.3/R23 — a connection is where the parent assigns the child's input, so
    // it is an assignment boundary and the child's port type is the target.
    // Connections bypass `addAssign` entirely (they are wires, not statements),
    // so before this they reached the emitter without literal resolution at all
    // and an unwidthed literal emitted at its own minimal width.
    val resolvedInstances = _instances.zipWithIndex.map { case (instance, index) =>
      val portTypes = instancePortTypes.getOrElse(index, Map.empty)
      instance.copy(connections = instance.connections.map { case (port, expr) =>
        port -> resolveInContext(expr, portTypes.get(port),
                                 LitCtx(s"`${instance.name}.$port`", instance.loc), litErrs)
      })
    }.toList
    if litErrs.nonEmpty then
      throw scalahdl.diag.ValidationFailed(litErrs.result())
    // R25 — a transition's guard is a *copy* of the condition the hardware was
    // written under, taken at the `goto` rather than shared with the statement
    // tree, so the resolution above does not reach it. Unresolved, a guard
    // reads `(count == 1'b43)` in the report where the emitted `if` says
    // `(count == 10'd43)` — which breaks the one promise the report makes,
    // that an edge label and the `if` it became read alike. The same resolver,
    // over the same expressions; an error here would already have been raised
    // by the statement it was copied from.
    val resolvedFsms = fsms.map { f =>
      f.copy(transitions = f.transitions.map(t =>
        t.copy(guard = t.guard.map(resolveExpr(_, LitCtx(s"the `${f.name}` transition guard", t.loc), litErrs)))))
    }.toList
    if litErrs.nonEmpty then
      throw scalahdl.diag.ValidationFailed(litErrs.result())
    // R27 — the check blocks. They are resolved by the same resolver as a
    // driver block's statements, because a literal in an assertion is written
    // against a signal exactly as one in an `if` is: `assert(count === 43,
    // ...)` must widen 43 against `count` or the check compares two different
    // numbers and passes for the wrong reason.
    val resolvedChecks = checkBlocks.toList.map { case (domain, (id, at)) =>
      CheckBlock(id, domain, stmts(id).map(resolveStmt(_, litErrs)).toList, at)
    }
    if litErrs.nonEmpty then
      throw scalahdl.diag.ValidationFailed(litErrs.result())
    Module(name, _ports.toList, resolvedSignals, resolvedMemories, resolvedInstances,
           resolvedDrivers, loc, resolvedFsms, resolvedChecks,
           relatedClocks = relatedClockFamilies)

  // ---- R6.1 literal resolution ----

  /** Resolve `None`-width literals in a statement tree. */
  private def resolveStmt(s: Stmt, errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): Stmt = s match
    // R27 — a check's condition is resolved like a `when`'s: it is a condition,
    // it has no assignment target, and its literals take their width from the
    // signals they are compared against.
    case Stmt.Check(block, cond, message, l) =>
      Stmt.Check(block, resolveExpr(cond, LitCtx("the `check` condition", l), errs), message, l)
    case Stmt.Assign(t, e, b, l) =>
      // R6.3 — the target supplies a width to a literal that is the whole
      // right-hand side, and its name to every literal diagnostic underneath.
      val (width, ctx) = targetContext(t, l)
      Stmt.Assign(t, resolveInContext(e, width, ctx, errs), b, l)
    case Stmt.When(c, thenS, elseS, l) =>
      Stmt.When(resolveExpr(c, LitCtx("the `when` condition", l), errs),
        thenS.map(resolveStmt(_, errs)), elseS.map(resolveStmt(_, errs)), l)
    case Stmt.Switch(skind, selector, cases, defaults, l) =>
      val resolvedSelector = resolveExpr(selector, LitCtx("the `switch` selector", l), errs)
      Stmt.Switch(skind, resolvedSelector,
        cases.map(c => SwitchCase(
          c.values.map(v => resolveCaseValue(v, resolvedSelector, c.loc, errs)),
          c.stmts.map(resolveStmt(_, errs)), c.loc)),
        defaults.map(_.map(resolveStmt(_, errs))), l)
    case other => other

  /** R6.1/R21.7/R22 — a case value takes its width from the selector, exactly
    * as a literal operand takes its width from its sibling. A case label has no
    * other context it could take one from, and R22 admits no implicit resize:
    * inferring the width is how `is(0.U)` is written against a `UInt(2)`
    * selector, not a way for a mis-sized value to slip through.
    *
    * A `.S` label against a `UInt` selector still takes the width, because
    * `SwitchPhase`'s `SWITCH_VALUE_TYPE_MISMATCH` names both kinds and can only
    * report that if the label has a type at all. Only the *unfixed* half of a
    * literal is filled from context; a fixed kind is never overridden. */
  private def resolveCaseValue(v: Expr, selector: Expr, at: Loc,
                               errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): Expr =
    resolveInContext(v, typeOfExpr(selector), LitCtx("this `is` label", at), errs)

  /** R6.3 — resolve an expression that stands alone in a position whose width
    * is known from elsewhere: a whole right-hand side, a register's `init`, an
    * instance port connection, or a case label.
    *
    * This is a **leaf rule**. It matches a bare `Expr.Lit` and nothing else, so
    * it cannot descend into a `Mux`, a `Cat`, or an operator — anything else
    * falls straight through to `resolveExpr`, where R6.1's sibling inference
    * and its guard apply unchanged. That is what keeps `x := a + 1.U` resolving
    * `1` from `a` rather than from `x`: by the time the assignment is
    * consulted, the literal is not the right-hand side, the `BinOp` is.
    *
    * `context` is the type the position expects, or `None` where it has none to
    * offer — an aggregate target (R7.2), a selector still holding an unresolved
    * literal. Then the literal is R6.1's uninferrable error exactly as it was
    * before R6.3. */
  private def resolveInContext(e: Expr, context: Option[Type], ctx: LitCtx,
                               errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): Expr = e match
    case lit @ Expr.Lit(_, _, _) => resolveLit(lit, context, ctx, errs)
    case other                   => resolveExpr(other, ctx, errs)

  /** R6.1/R6.4/R21.7 — fill in whichever of a literal's two properties the
    * context is still owed, and range-check the result.
    *
    * Width and kind are independent: `.U` arrives with a kind and no width, a
    * bare `Int` with neither, and `2.U(5)` with both. A property the literal
    * already carries is **never** overridden — that is what keeps `.U`/`.S`
    * meaningful and leaves a mismatched pair for `WidthPhase` to report against
    * the operator or the assignment, where the message can name both sides. */
  private def resolveLit(lit: Expr.Lit, context: Option[Type], ctx: LitCtx,
                         errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): Expr =
    val width = lit.width.orElse(context.flatMap(ExprType.targetWidth))
    val kind = lit.kind.orElse(context.flatMap(LitKind.of))
    (width, kind) match
      case (Some(w), Some(k)) =>
        checkLitFits(lit.value, w, k, ctx, errs)
        Expr.Lit(lit.value, Some(w), Some(k))
      case _ =>
        errs += litErr(lit.value, kind, ctx)
        Expr.Lit(lit.value, Some(width.getOrElse(1)),
                 Some(kind.getOrElse(LitKind.Unsigned)))

  /** R6.3 — the type an assignment target offers a whole-RHS literal, and the
    * name to report it against. The type is the *leaf's*: a bundle field, a
    * vec element, or the signal itself (R14), read through the same shared walk
    * `WidthPhase` checks the assignment with. */
  private def targetContext(t: Lhs, at: Loc): (Option[Type], LitCtx) =
    val target = _signals.find(_.id == t.rootId)
    val ctx = LitCtx(target.map(s => s"`${s.name}`").getOrElse("the assignment target"),
                     at, target.map(_.loc).getOrElse(Loc.NoLoc))
    (Some(ExprType.lhsType(t, typeTable)), ctx)

  /** Resolve `None`-width literals in an expression tree (R6.1, R21.7). A
    * literal takes its width from its position: the sibling operand for the
    * binary operators and the `Mux` arms, its own minimal width for a shift
    * amount, and nothing at all in a `Cat`/`Fill` part, where an inferred
    * width would silently change the width of the whole. A literal with no
    * such context is an error (the assignment-boundary guard). */
  private def resolveExpr(e: Expr, ctx: LitCtx,
                          errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): Expr = e match
    case lit @ Expr.Lit(_, _, _) =>
      // A literal reached without an inferring context. Anything it is still
      // missing is unresolvable here; anything it carries is range-checked
      // exactly as an inferred one is (R21.7 — the explicit path used to skip
      // negatives entirely). Every inferring position handles its literals
      // before recursing, so this only fires where there is genuinely no
      // context — `Cat(a, 1.U)`, a two-literal `Mux`.
      resolveLit(lit, None, ctx, errs)
    case Expr.BinOp(op, lhs, rhs) =>
      if ExprType.Op.isShift(op) then
        // R21.1/R21.7 — the amount is not a value in the result's number
        // system, so it takes its own minimal width rather than the left
        // operand's. A wider encoding of the same amount cannot change the
        // result, which is why this is safe here and nowhere else.
        Expr.BinOp(op, resolveExpr(lhs, ctx, errs),
          resolveShiftAmount(rhs, ctx, errs))
      else
        // Infer None-width literals from the other operand BEFORE recursing
        // (so the literal case above doesn't fire).
        val (lr, rr) = resolveSiblings(lhs, rhs, ctx, errs)
        Expr.BinOp(op, lr, rr)
    case Expr.UnOp(op, arg) => Expr.UnOp(op, resolveExpr(arg, ctx, errs))
    case Expr.TypeCast(to, inner) =>
      Expr.TypeCast(to, resolveExpr(inner, ctx, errs))
    case Expr.Cdc(primitive, inner, loc) =>
      Expr.Cdc(primitive, resolveExpr(inner, ctx, errs), loc)
    case Expr.Mux(sel, whenTrue, whenFalse) =>
      // R21.7 — an arm takes the other arm's width; two literal arms have no
      // context. Before R21 the arms were recursed into without inferring, so
      // `Mux(sel, a, 0.U)` reported an uninferrable literal.
      val (tr, fr) = resolveSiblings(whenTrue, whenFalse, ctx, errs)
      Expr.Mux(resolveExpr(sel, ctx, errs), tr, fr)
    case Expr.Field(base, name) => Expr.Field(resolveExpr(base, ctx, errs), name)
    case Expr.Index(base, idx)  => Expr.Index(resolveExpr(base, ctx, errs),
                                              resolveExpr(idx, ctx, errs))
    case Expr.Slice(base, hi, lo) => Expr.Slice(resolveExpr(base, ctx, errs), hi, lo)
    case Expr.VarSlice(base, lo, w) =>
      Expr.VarSlice(resolveExpr(base, ctx, errs), resolveExpr(lo, ctx, errs), w)
    case Expr.Cat(parts) =>
      Expr.Cat(parts.zipWithIndex.map { case (p, i) =>
        resolvePart(p, s"Cat part ${i + 1} of ${parts.length}", ctx, errs) })
    case Expr.Repeat(n, inner) =>
      Expr.Repeat(n, resolvePart(inner, s"the Fill($n, …) part", ctx, errs))
    case Expr.Resize(to, inner) => Expr.Resize(to, resolveExpr(inner, ctx, errs))
    case ref @ Expr.Ref(_) => ref

  /** Whether a literal is still owed something by its context — a width, a
    * kind, or both (R6.1/R6.4). */
  private def isOpen(e: Expr): Boolean = e match
    case Expr.Lit(_, w, k) => w.isEmpty || k.isEmpty
    case _                 => false

  /** Resolve two sibling operands that must agree — the two sides of a binary
    * operator, or the two arms of a `Mux`. An open literal takes whatever it
    * lacks from the other's (already-resolved) type; both open is an error. */
  private def resolveSiblings(lhs: Expr, rhs: Expr, ctx: LitCtx,
                              errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): (Expr, Expr) =
    // First resolve everything already answerable — Refs, nested expressions,
    // and fully-fixed literals, which are range-checked like any other (R21.7).
    // Only an *open* literal is held back: recursing into it here would fire
    // the "uninferrable" error before its sibling has had the chance to supply
    // what it is missing.
    def resolveClosed(e: Expr): Expr = if isOpen(e) then e else resolveExpr(e, ctx, errs)
    val lr0 = resolveClosed(lhs)
    val rr0 = resolveClosed(rhs)
    // R21.7 — the sibling's type is read through the shared typer, so it is
    // knowable through a slice, a cast, a resize, a shift, a concatenation, or
    // a product: `instr(31, 20) + 1.U` resolves `1` to 12 bits.
    (lr0, rr0) match
      case (l: Expr.Lit, r: Expr.Lit) if isOpen(l) && isOpen(r) =>
        // Two open literals: neither can answer for the other, so neither is
        // worth naming — the expression as a whole is what has no context.
        val pair = ctx.copy(name = "this expression", decl = Loc.NoLoc)
        (resolveLit(l, None, pair, errs), resolveLit(r, None, pair, errs))
      case (l: Expr.Lit, r) if isOpen(l) =>
        (resolveLit(l, typeOfExpr(r), siblingCtx(r, ctx), errs), r)
      case (l, r: Expr.Lit) if isOpen(r) =>
        (l, resolveLit(r, typeOfExpr(l), siblingCtx(l, ctx), errs))
      case _ => (lr0, rr0)

  /** R6.1/R19 — when a literal takes its width from its **sibling** rather
    * than from the enclosing statement's target, the sibling is what the
    * diagnostic has to name. In `eq := (flag === 100)` the one bit came from
    * `flag`; reporting it against `eq` names a signal that had no part in it,
    * and `eq` happening to be one bit wide too is a coincidence of this
    * example rather than a rule.
    *
    * Only a named signal renames the context, because only a named signal has
    * a declaration to point the `note:` at. A slice, a cast or a product
    * supplied the width just as really, but has no line of its own, so it is
    * named for what it is and the note is dropped. */
  private def siblingCtx(sibling: Expr, ctx: LitCtx): LitCtx = sibling match
    case Expr.Ref(id) =>
      _signals.find(_.id == id)
        .map(s => ctx.copy(name = s"`${s.name}`", decl = s.loc))
        .getOrElse(ctx)
    case _ => ctx.copy(name = "the other operand", decl = Loc.NoLoc)

  /** R21.1/R21.7 — a shift amount's width comes from its value: `max(1,
    * bitLength)`, and its kind is unsigned, because the amount is not a value
    * in the result's number system at all. This is the only position where a
    * literal takes anything from itself rather than its context. */
  private def resolveShiftAmount(e: Expr, ctx: LitCtx,
                                 errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): Expr = e match
    case lit @ Expr.Lit(v, _, _) if isOpen(lit) =>
      Expr.Lit(v, lit.width.orElse(Some(v.bitLength max 1)),
               lit.kind.orElse(Some(LitKind.Unsigned)))
    case other => resolveExpr(other, ctx, errs)

  /** R21.5/R21.7 — a `Cat` or `Fill` part must carry its own width, because a
    * concatenation's width is the sum of its parts: a part whose width was
    * inferred would silently change the width of the whole. */
  private def resolvePart(e: Expr, position: String, ctx: LitCtx,
                          errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): Expr = e match
    case Expr.Lit(v, None, kind) =>
      errs += partWidthErr(v, kind, position, ctx)
      Expr.Lit(v, Some(1), kind.orElse(Some(LitKind.Unsigned)))
    case other => resolveExpr(other, ctx, errs)

  /** The width of an expression, through R21.6's shared typer. `None` only
    * when an unresolved literal is still inside it. */
  private def widthOfExpr(e: Expr): Option[Int] =
    ExprType.widthOpt(e, typeTable)

  /** The type of an expression, through R21.6's shared typer — what a context
    * offers an open literal beside it (R6.1/R6.4). */
  private def typeOfExpr(e: Expr): Option[Type] =
    ExprType.typeOpt(e, typeTable)

  /** R21.7 — check a literal fits its inferred or explicit width, at both ends
    * of the range and with the right range for its kind. A signed literal's
    * admissible range is the two's-complement range of its width,
    * `-2^(w-1) … 2^(w-1) - 1`, inclusive at both ends: `-8` fits in 4 bits.
    * The three unsigned kinds admit no negative value at all, which is the one
    * thing R6.4's context-taken kind must never paper over. */
  private def checkLitFits(v: BigInt, w: Int, kind: LitKind,
                           ctx: LitCtx,
                           errs: mutable.ListBuffer[scalahdl.diag.Diagnostic]): Unit =
    val signed = kind == LitKind.Signed
    if signed then
      if requiredWidth(v, signed = true) > w then errs += litOverflow(v, w, kind, ctx)
    else if v < BigInt(0) then
      errs += negativeUnsignedLit(v, ctx)
    else if requiredWidth(v, signed = false) > w then
      errs += litOverflow(v, w, kind, ctx)

  /** The narrowest width that represents `v`: `bitLength` unsigned, and
    * `bitLength + 1` signed, the extra bit being the sign. `BigInt.bitLength`
    * already excludes the sign bit for negatives, so `-8` needs `3 + 1 = 4`
    * and `-9` needs `4 + 1 = 5`. */
  private def requiredWidth(v: BigInt, signed: Boolean): Int =
    if signed then v.bitLength + 1 else v.bitLength max 1

  /** R6.1/R6.4 — a literal whose context did not supply what it was missing.
    * The summary names the missing half, because the fixes differ: a widthless
    * `.U` needs a width, and a bare `Int` in a position with no type at all
    * needs a suffix as well. */
  private def litErr(v: BigInt, kind: Option[LitKind],
                     ctx: LitCtx): scalahdl.diag.Diagnostic =
    val suffix = litSuffix(kind.contains(LitKind.Signed))
    val (what, rule) =
      if kind.isEmpty then ("width or kind", "a bare `Int` takes both from its context")
      else ("width", "provide a width or use it in an expression with context")
    litDiag(ctx,
      summary = s"literal `$v` has no inferrable $what ($rule)",
      note = s"${ctx.name} supplies no $what here",
      fix = s"write `$v.$suffix(<width>)` explicitly")

  /** R21.7 — the over-range diagnostic names the value, the width it needs,
    * the width it has, and a fix whose signedness matches the literal's. */
  private def litOverflow(v: BigInt, w: Int, kind: LitKind,
                          ctx: LitCtx): scalahdl.diag.Diagnostic =
    val signed = kind == LitKind.Signed
    val required = requiredWidth(v, signed)
    val range =
      if signed then s"a signed $w-bit literal holds ${-(BigInt(1) << (w - 1))} … ${(BigInt(1) << (w - 1)) - 1}"
      else s"an unsigned $w-bit literal holds 0 … ${(BigInt(1) << w) - 1}"
    litDiag(ctx,
      summary = s"literal `$v` requires $required bits but context provides $w ($range)",
      note = s"${ctx.name} supplies ${bits(w)} here, and `$v` needs ${bits(required)}",
      fix = s"widen the context, or write `$v.${litSuffix(signed)}($required)` explicitly")

  private def negativeUnsignedLit(v: BigInt, ctx: LitCtx): scalahdl.diag.Diagnostic =
    litDiag(ctx,
      summary = s"literal `$v` is negative but written as unsigned (a literal never adopts a sibling's signedness)",
      note = s"${ctx.name} is unsigned here, so `$v` has no representation",
      fix = s"write `$v.S` for a signed literal, or use a non-negative value")

  private def partWidthErr(v: BigInt, kind: Option[LitKind],
                           position: String, ctx: LitCtx): scalahdl.diag.Diagnostic =
    val suffix = litSuffix(kind.contains(LitKind.Signed))
    litDiag(ctx,
      summary = s"literal `$v` has no width, and $position must be width-known (a concatenation's width is the sum of its parts)",
      note = s"$position must carry its own width",
      fix = s"give the part an explicit width, e.g. `$v.$suffix(1)`")

  /** R19 — the shape every literal diagnostic shares: the caret on the
    * statement the literal was written in, the declaration that supplied its
    * context as a `note:` beneath, and a fix naming an API that exists.
    *
    * Where the context has no location — a synthetic tree assembled by a
    * library helper — `primaryLoc` falls back to `NoLoc` and the renderer emits
    * the header alone, which is what every one of these used to do
    * unconditionally. `note` is dropped with it, for `WidthPhase.widthErr`'s
    * reason: caret text under no caret names nothing. */
  private def litDiag(ctx: LitCtx, summary: String, note: String,
                      fix: String): scalahdl.diag.Diagnostic =
    val located = ctx.loc != Loc.NoLoc
    scalahdl.diag.Diagnostic(
      severity = scalahdl.diag.Severity.Error,
      code = scalahdl.diag.Code.WIDTH_MISMATCH,
      summary = summary,
      primaryLoc = if located then ctx.loc else ctx.decl,
      primaryNote = Option.when(located)(note),
      related = List(RelatedLoc(ctx.decl, s"${ctx.name} declared here"))
        .filter(r => r.loc != Loc.NoLoc && r.loc != ctx.loc),
      fix = Some(fix))

  /** "1 bit" / "8 bits" — a width reads as prose in a caret note. */
  private def bits(w: Int): String = if w == 1 then "1 bit" else s"$w bits"

  private def litSuffix(signed: Boolean): String = if signed then "S" else "U"

  // ---- internals ----

  private def resolveName(src: Source, t: Type): String =
    src.name.getOrElse {
      // R2: anonymous trees never receive a declaration, so a hardware value
      // that has no name cannot be declared. The throw is the loud failure
      // that keeps R2 honest.
      throw ElabError(s"anonymous hardware value of type $t has no name; bind it to a `val`", src.loc)
    }

  private def declareSignal(name: String, t: Type, kind: SignalKind,
                            init: Option[Expr], loc: Loc, domain: ClockDomain,
                            encoding: Option[EncodingInfo] = None,
                            powerUp: Option[Expr] = None,
                            onto: Option[DslSignal] = None): DslSignal =
    // R1 collision: do not throw here — record the signal so the NamePhase can
    // report the duplicate with both declaration locations. Last-wins for
    // name→id lookup (subsequent references bind to the newest declaration).
    // R27 — a declaration inside `simOnly` would emit a net outside the guard,
    // which is the bit-identity the construct promises broken by a line that
    // looks harmless. Every declaration in the library funnels through here.
    checkNotInSimOnly(s"`$name` is declared",
      "declare it outside the block and read it inside — a `simOnly` may read every signal " +
      "in the module and may create none", loc)
    val id = nextId()
    byName(name) = id
    _signals += Signal(id, name, t, kind, init, loc, powerUp, domain, encoding)
    stmts(id) = mutable.ListBuffer.empty
    domains(id) = domain
    // R31 — a handle made before the signal existed becomes the signal's own
    // handle, rather than a second one kept in step with it.
    val handle = onto match
      case Some(existing) =>
        require(existing.typeOf == t,
          s"`$name` is declared as $t onto a handle of type ${existing.typeOf}")
        existing.bindPath(id, name, kind == SignalKind.Reg, Nil)
        existing
      case None => new DslSignal(id, name, t, kind == SignalKind.Reg, Nil)
    handle.bindOwner(this)   // R23 — `:=` checks this before recording
    signalHandles(id) = handle
    handle

  private[scalahdl] def signalHandle(id: SignalId): Option[DslSignal] = signalHandles.get(id)

  /** R5.6 — whether `id` is a `Clock` signal this module declared: a port, a
    * `Wire(Clock())`, or the parent-side net of a child's output clock. */
  private[scalahdl] def declaresClock(id: SignalId): Boolean =
    _signals.exists(s => s.id == id && s.typeOf == Type.Clock)

  /** R21.6 — the signal-type lookup the shared typer reads. Declared signals
    * only: an expression can reference a signal only after it exists, so a
    * missing id is malformed IR rather than a typing question. */
  private[scalahdl] def typeTable: SignalId => Type =
    id => _signals.find(_.id == id).map(_.typeOf).getOrElse(
      throw ExprTypeError(s"expression references an undeclared signal id ${id.value}"))

  private[scalahdl] def signalIdForPort(name: String): Option[SignalId] =
    _ports.find(_.name == name).map(_.signalId)

  private def scalaHdlBlackBoxDiagnostic(code: scalahdl.diag.Code,
                                         summary: String): scalahdl.diag.Diagnostic =
    scalahdl.diag.Diagnostic(scalaHdlSeverityError, code, summary, Loc.NoLoc)

  private def scalaHdlSeverityError: scalahdl.diag.Severity = scalahdl.diag.Severity.Error

  /** Record a statement into the innermost open scope — the current `when` arm
    * or `switch` alternative — marking that scope as touching the signal, or
    * onto the signal's own buffer when no scope is open. Returns the buffer it
    * landed in, which is what lets a reopening `otherwise` withdraw it.
    *
    * Recording anything ends the previous `when` chain's reopen window: a
    * statement between a `when` and its `otherwise` means the two are no
    * longer adjacent, and silently attaching the else arm across it would
    * reorder the driver block (R12). */
  private def recordStmt(id: SignalId, stmt: Stmt): mutable.ListBuffer[Stmt] =
    lastChain = None
    val buffer = scopeStack.headOption match
      case Some(scope) =>
        scope.touched += id
        sinkFor(scope)
      case None => stmts(id)
    buffer += stmt
    buffer

  /** The buffer a scope is currently collecting into. A `switch` body outside
    * any alternative has none: the statement would have no place in the case
    * structure, and quietly hoisting it out of the switch would change when it
    * runs (R12/R22). */
  private def sinkFor(scope: Scope): mutable.ListBuffer[Stmt] = scope match
    case frame: CondFrame => if frame.inElse then frame.elseArm else frame.thenArm
    case frame: SwitchFrame =>
      frame.open.map(_.stmts).getOrElse(
        throw switchFailure(scalahdl.diag.Code.SWITCH_ARM_CONTEXT,
          "a statement inside `switch(...)` must be inside an `is(...)` or `default` alternative",
          frame.loc, related = Nil,
          fix = "move it into an alternative, or before the `switch` if it is meant to run unconditionally"))

  /** Mutable elaboration-only memory accumulator. */
  final class MemoryBuilder(val name: String, val kind: MemoryKind, val depth: Int,
                            val elemType: Type, val init: Option[MemoryInit],
                            val loc: Loc):
    private val reads = mutable.ListBuffer.empty[MemoryReadPort]
    private val writes = mutable.ListBuffer.empty[MemoryWritePort]

    def addRead(result: SignalId, address: Expr, synchronous: Boolean,
                policy: ReadDuringWrite, domain: ClockDomain, loc: Loc,
                enable: Option[Expr] = None): Unit =
      reads += MemoryReadPort(result, address, synchronous, policy, domain, loc, enable)

    def addWrite(address: Expr, data: Expr, enable: Option[Expr],
                 domain: ClockDomain, loc: Loc): Unit =
      writes += MemoryWritePort(address, data, enable, domain, loc)

    /** `resolve` is handed each expression together with the location of the
      * port it came from, so a literal in a `write` reports at that `write`
      * rather than at the `Memory` declaration shared by every port. */
    def freeze(resolve: (Expr, Loc) => Expr): Memory =
      Memory(name, kind, depth, elemType, init,
        reads.toList.map(p => p.copy(address = resolve(p.address, p.loc),
          enable = p.enable.map(resolve(_, p.loc)))),
        writes.toList.map(p => p.copy(address = resolve(p.address, p.loc),
          data = resolve(p.data, p.loc),
          enable = p.enable.map(resolve(_, p.loc)))),
        loc)

object Builder:

  /** R19 / R6.1 — where a literal was written, and what supplied (or failed to
    * supply) the width it was owed.
    *
    * The builder's literal diagnostics are raised during `freeze`, long after
    * the DSL call that wrote them has returned, so unlike `WidthPhase` they
    * have no statement in hand to point at. This carries one: `loc` is the
    * statement the literal was written in — the `:=`, the `when`, the `is`, the
    * memory operation — captured by the R1 macro at construction and threaded
    * down the resolver alongside the context's `name`. Without it every literal
    * error rendered as a bare header with no `-->` line, which is the one shape
    * R19 exists to rule out.
    *
    * `decl` is R19's *other* location, where the context has one: the
    * declaration whose type offered the width. A switch selector or a memory
    * port has no declaration of its own, so it stays `NoLoc` and the note is
    * dropped. */
  final case class LitCtx(name: String, loc: Loc, decl: Loc = Loc.NoLoc)

  /** R23 — what an instance needs after its ports are bound: the child's port
    * declaration order (the emission order for connections), the data inputs
    * the parent still owes a connection, and the child module name and
    * instantiation site for diagnostics. */
  final case class InstancePorts(
    declarationOrder: List[String],
    requiredInputs: List[String],
    moduleName: String,
    loc: Loc
  )

  /** An open elaboration scope. `touched` is the set of signals that received
    * a statement anywhere inside it — the finished conditional or switch is
    * recorded on each of them when the scope closes. */
  sealed trait Scope:
    val touched: mutable.Set[SignalId] = mutable.Set.empty

  /** A `when` frame under construction. */
  final class CondFrame(val cond: Expr, val loc: Loc) extends Scope:
    val thenArm: mutable.ListBuffer[Stmt] = mutable.ListBuffer.empty
    val elseArm: mutable.ListBuffer[Stmt] = mutable.ListBuffer.empty
    var inElse: Boolean = false

  /** R22 — a `switch` frame under construction. Alternatives accumulate in the
    * order the elaborator observes them, which is call order whether they were
    * written out or produced by a Scala loop; `open` is the alternative
    * currently collecting statements. */
  final class SwitchFrame(val kind: SelectKind, val selector: Expr, val loc: Loc) extends Scope:
    val cases: mutable.ListBuffer[SwitchCase] = mutable.ListBuffer.empty
    var defaultArm: Option[SwitchCase] = None
    var open: Option[OpenArm] = None

  /** One alternative while its body elaborates. `values` is `None` for the
    * `default` arm, which is how the two are told apart at close. */
  final class OpenArm(val values: Option[List[Expr]], val loc: Loc):
    val stmts: mutable.ListBuffer[Stmt] = mutable.ListBuffer.empty

  /** R25 — a state machine while its body elaborates. It owns an ordinary
    * `SwitchFrame`, so every R22 mechanism — projection, duplicate values,
    * completeness, emission — applies to a machine without knowing it is one;
    * what is kept here is only the graph, which the switch has no reason to
    * record. */
  final class OpenFsm(
    val name: String,
    val reg: DslSignal,
    val encoding: scalahdl.dsl.Encoding,
    val frame: SwitchFrame,
    val initCode: Option[BigInt],
    val powerUpCode: Option[BigInt],
    val loc: Loc
  ):
    val info: EncodingInfo = encoding.info
    /** The states the machine can start from: the reset state, then the
      * power-up state when it is another. */
    def entryCodes: List[BigInt] = (initCode.toList ++ powerUpCode.toList).distinct
    val states: mutable.ListBuffer[FsmState] = mutable.ListBuffer.empty
    val transitions: mutable.ListBuffer[FsmTransition] = mutable.ListBuffer.empty

    /** The arm currently collecting, and the states that select it:
      * `Some(List(code))` inside `in(code)`, `Some(List(a, b))` inside R25.1's
      * `in(a, b)`, `Some(Nil)` inside `default` — which names no state — and
      * `None` between arms. */
    var openArm: Option[List[BigInt]] = None

    def stateNameOf(code: BigInt): String = info.qualified(code).getOrElse(code.toString)

  /** A `when` chain being built: its identity, and the stack depth it started
    * at, which is how many frames `endWhen` has to close. */
  final case class OpenChain(id: Int, base: Int)

  /** A closed chain, retained only until the next statement, so an immediately
    * following `elsewhen`/`otherwise` can reopen it. `undo` names the buffers
    * one frame's close appended to, one entry per signal it wrote. */
  final case class ChainLink(frame: CondFrame, undo: List[mutable.ListBuffer[Stmt]])
  final case class ClosedChain(id: Int, base: Int, links: List[ChainLink])

  final case class ElabError(msg: String, loc: Loc) extends RuntimeException(msg):
    override def toString: String = s"ElabError: $msg at $loc"

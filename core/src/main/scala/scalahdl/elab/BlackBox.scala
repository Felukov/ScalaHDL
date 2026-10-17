package scalahdl.elab

import scala.collection.mutable
import scalahdl.clock.{ClockDomain, ResetKind}
import scalahdl.core.{BlackBoxParam, BlackBoxParamValue, BlackBoxPortSignature, Direction, SignalId, Type}
import scalahdl.dsl.DslSignal

/** R18 — Declaration-only external module wrapper.
  *
  * A blackbox executes only enough Scala construction to collect its ports,
  * literal parameters, and mandatory output-domain contract. It is never
  * frozen as an emitted module body. */
abstract class BlackBox extends Module:
  /** Fixed external module name. Subclasses must override this method; unlike
    * generated modules, no parameter-derived suffix is ever added. */
  override def moduleName: String

  private val _parameters = mutable.ListBuffer.empty[BlackBoxParam]
  private val _newDomains = mutable.LinkedHashMap.empty[SignalId, BlackBoxDomain]
  private val _outputDomains = mutable.LinkedHashMap.empty[SignalId, BlackBoxDomain]
  private var _enclosing = Option.empty[BlackBoxDomain]
  private val _unpacked = mutable.LinkedHashSet.empty[SignalId]
  private val _combPaths = mutable.LinkedHashSet.empty[(SignalId, SignalId)]

  /** R18.3 — Explicit, ordered parameter registration. */
  protected final def param(name: String, value: Int): Unit =
    _parameters += BlackBoxParam(name, BlackBoxParamValue.Integer(value))

  protected final def param(name: String, value: Double): Unit =
    _parameters += BlackBoxParam(name, BlackBoxParamValue.Real(value))

  protected final def param(name: String, value: String): Unit =
    _parameters += BlackBoxParam(name, BlackBoxParamValue.Str(value))

  /** R18.2 — Declare an output clock as a new, unrelated domain. The returned
    * handle is rebound to the parent-side clock net when this object is
    * instantiated. */
  protected final def newDomain(clock: DslSignal): BlackBoxDomain =
    require(clock.typeOf == Type.Clock,
      s"newDomain requires an output Clock port, got ${clock.typeOf}")
    require(builder.portDirection(clock.id).contains(Direction.Out),
      "newDomain requires an output clock port")
    val domain = new BlackBoxDomain(clock.id, clock.name)
    _newDomains(clock.id) = domain
    domain

  /** R18.4 — The domain this blackbox is *instantiated in*, for a box that
    * joins a clock rather than creating one.
    *
    * R18.2's `newDomain` covers the PLL: an output clock the library cannot
    * relate to anything it already knows. The other shape is far more common
    * and had nowhere to go — a vendored peripheral with a `clk` *input* and
    * data outputs synchronous to it. This is that declaration, and it is bound
    * at `inst(...)` to the domain in scope there, exactly as R13.2 binds a
    * generated child's clock port.
    *
    * The obligation R18.2 exists for survives: the blackbox still declares,
    * output by output, and nothing is inferred. What it declares is which of
    * the two relations holds. */
  protected final def enclosingDomain: BlackBoxDomain =
    _enclosing.getOrElse {
      val domain = new BlackBoxDomain(SignalId(-1), "<enclosing>")
      _enclosing = Some(domain)
      domain
    }

  /** R18.2 / R18.4 — Associate one non-clock data output with a declared
    * domain: either one this blackbox creates ([[newDomain]]) or the one it is
    * instantiated in ([[enclosingDomain]]). */
  protected final def outputDomain(output: DslSignal, domain: BlackBoxDomain): Unit =
    require(builder.portDirection(output.id).contains(Direction.Out),
      "outputDomain requires an output port")
    require(output.typeOf != Type.Clock,
      "output clocks are declared with newDomain, not outputDomain")
    require(_newDomains.values.exists(_ eq domain) || _enclosing.exists(_ eq domain),
      "outputDomain must reference this blackbox's own newDomain or its " +
      "enclosingDomain")
    _outputDomains(output.id) = domain

  /** R18.5 — This port is an **unpacked array** on the external module, and
    * connects whole.
    *
    * R14.1 expands a `Vec` at a port into one scalar per element, and says why:
    * Verilog-2001 forbids array ports. That is a fact about the modules this
    * library *emits*. An external module is somebody else's, may be
    * SystemVerilog, and may declare `output reg [31:0] io_lba[DEVS]` — one
    * port carrying an array, which cannot be connected leaf by leaf and cannot
    * be connected to a packed vector either. Both are hard errors in every
    * tool, so a blackbox that has one is a blackbox that cannot be declared at
    * all.
    *
    * The parent side needs nothing new: an internal `Vec` signal is *already*
    * emitted as an unpacked net array (R14.1's other half), which is exactly
    * what such a port must be connected to. So this declares one thing — that
    * the child's port is an array rather than a group of scalars — and the
    * connection follows.
    *
    * Only a `Vec` can be one. A `Bundle` at an external port is a struct, and
    * a struct is a different declaration with a type name in it that this
    * library has no way to know. */
  protected final def unpacked(port: DslSignal): Unit =
    require(port.typeOf.isInstanceOf[Type.Vec],
      s"unpacked requires a Vec port, got ${port.typeOf}")
    require(builder.portDirection(port.id).isDefined,
      "unpacked requires a port of this blackbox")
    _unpacked += port.id

  /** R28 — declare that this external module carries a combinational path from
    * input port `from` to output port `to`.
    *
    * A blackbox has no body, so the dependency graph cannot see through it, and
    * the two things it could assume instead are both wrong: assume nothing
    * passes and a ring closed through a vendored gate goes unreported; assume
    * everything does and every registered peripheral in a feedback path becomes
    * an error nobody can keep enabled. So the box states it, exactly as R18.2
    * has it state its domains and R18.5 its unpacked ports — and a box that
    * declares nothing is *assumed* transparent-free, which the report says out
    * loud rather than leaving to be discovered. */
  protected final def combPath(from: DslSignal, to: DslSignal): Unit =
    require(builder.portDirection(from.id).contains(Direction.In),
      "combPath takes an input port as its source")
    require(builder.portDirection(to.id).contains(Direction.Out),
      "combPath takes an output port as its destination")
    _combPaths += (from.id -> to.id)

  private[scalahdl] final def blackBoxCombPaths: Set[(SignalId, SignalId)] = _combPaths.toSet

  private[scalahdl] final def blackBoxUnpacked: Set[SignalId] = _unpacked.toSet

  private[scalahdl] final def blackBoxParameters: List[BlackBoxParam] = _parameters.toList
  private[scalahdl] final def blackBoxNewDomains: Map[SignalId, BlackBoxDomain] = _newDomains.toMap
  private[scalahdl] final def blackBoxOutputDomains: Map[SignalId, BlackBoxDomain] = _outputDomains.toMap
  private[scalahdl] final def blackBoxEnclosingDomain: Option[BlackBoxDomain] = _enclosing

  private[scalahdl] final def blackBoxSignature: List[BlackBoxPortSignature] =
    builder.portsList.map(p => BlackBoxPortSignature(p.name, p.direction, p.typeOf))
end BlackBox

/** An output-domain declaration becomes concrete when the blackbox is bound in
  * its parent. The wrapper keeps `pll.outDomain` stable while each freshly
  * constructed blackbox instance resolves it exactly once. */
final class BlackBoxDomain private[elab] (val clockPortId: SignalId, val clockPortName: String):
  private var resolved: Option[ClockDomain] = None

  private[scalahdl] def bind(domain: ClockDomain): Unit =
    require(resolved.isEmpty, "blackbox domain is already bound to an instance")
    resolved = Some(domain)

  private[scalahdl] def clockDomain: ClockDomain = resolved.getOrElse(
    throw IllegalStateException("blackbox output domain is not available before inst(...)"))

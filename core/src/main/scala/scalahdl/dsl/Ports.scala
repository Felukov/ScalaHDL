package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module

/** R10 — Ports declared directly with `In`/`Out`; no `io` wrapper. Verilog
  * port names are exactly the `val` names (R1) — no prefix to strip. Ports
  * appear in the IR `Module` in declaration order; the emitter writes them
  * in that order.
  *
  * `In`/`Out` are `inline def`s so the binding `val`'s name is captured by R1.
  * Ports are not clocked logic, so they do not require a `using ClockDomain`
  * — only internal signals and registers do (R4). This lets a module declare
  * its `clk`/`rst` ports before supplying the `given ClockDomain` built from
  * them (R13.1).
  *
  * R07: a `Bundle` argument binds the bundle's field leaves to the
  * allocated signal, so `req.addr` is a real Scala `val` resolving to a
  * `DslSignal` sub-handle. A scalar `Type` argument returns a plain
  * `DslSignal`. The bundle overload is by-name so the bundle is constructed
  * fresh and bound. */

/** `In(t)` — an input port of the given scalar type. `Source.capture` is
  * passed directly so the R1 macro's splice owner resolves to the user's
  * binding `val`. */
inline def In(t: Type)(using m: Module): DslSignal =
  m.builder.addPort(Source.capture, Direction.In, t)

/** `In(b)` — an input port carrying a `Bundle` (R07). The bundle's field
  * leaves are bound to the allocated signal, so `req.addr` resolves `addr` as
  * a real symbol and yields a `DslSignal` sub-handle. By-name so the bundle is
  * constructed fresh. */
inline def In[B <: Bundle](b: => B)(using m: Module, ev: B <:< Bundle): B =
  Bundle.bind(m.builder.addPort(Source.capture, Direction.In, b.toType), b)

/** R24 — `In(AluOp())` — an input port carrying a named encoding. The declared
  * type is the `UInt` the encoding stands for; the encoding travels with the
  * signal so a diagnostic about it can name its members. */
inline def In(e: Encoding)(using m: Module): DslSignal =
  m.builder.addPort(Source.capture, Direction.In, e.toType, Some(e.info))

/** R05.7 — the marker that says an input is driven by something with no
  * timing relationship to this design's clocks. */
object Asynchronous

/** R05.7 — `In(t, Asynchronous)`: a pin a part with its own crystal drives.
  * The port carries a domain foreign to every clock instead of a port's
  * neutrality, so reading it in clocked logic is a crossing and `SyncChain` is
  * the one primitive that may take it. What *arrives* at such a pin is not
  * constrained: an input declares what this module may assume, and it assumes
  * nothing. */
inline def In(t: Type, timing: Asynchronous.type)(using m: Module): DslSignal =
  // Positional, deliberately: a *named* argument after `Source.capture` makes
  // Scala bind the earlier arguments to synthetic vals, and R1 then captures
  // the compiler's name instead of the user's `val`.
  m.builder.addPort(Source.capture, Direction.In, t, None, None, true)

/** `Out(t)` — an output port of the given scalar type. */
inline def Out(t: Type)(using m: Module): DslSignal =
  m.builder.addPort(Source.capture, Direction.Out, t)

/** R24 — `Out(AluOp())`. See `In(e)`. */
inline def Out(e: Encoding)(using m: Module): DslSignal =
  m.builder.addPort(Source.capture, Direction.Out, e.toType, Some(e.info))

/** `Out(b)` — an output port carrying a `Bundle` (R07). See `In(b)`. */
inline def Out[B <: Bundle](b: => B)(using m: Module, ev: B <:< Bundle): B =
  Bundle.bind(m.builder.addPort(Source.capture, Direction.Out, b.toType), b)

/** R31 — `Master(i)` declares an interface's ports as written: the endpoint
  * is named by the `val`, each port `<val>_<field>`, and each direction is
  * the one the declaration gives it. The interface instance is returned with
  * its field `val`s bound to those ports. */
inline def Master[I <: Interface](i: => I)(using m: Module): I =
  Interface.declare(Source.capture, Interface.Role.Master, i)

/** R31 — `Slave(i)` declares the mirror image: every direction the other way.
  * See `Master(i)`. */
inline def Slave[I <: Interface](i: => I)(using m: Module): I =
  Interface.declare(Source.capture, Interface.Role.Slave, i)

/** R31 — `n` endpoints of one interface: `val client = Slave(Vec(4, MemAccess()))`,
  * whose pins are `client_0_req_valid`, `client_1_req_valid`, and so on. The
  * elements come back as an ordinary `IndexedSeq`, because n endpoints are n
  * endpoints and there is no signal to hand back. See [[InterfaceVec]]. */
inline def Master[I <: Interface](v: InterfaceVec[I])(using m: Module): IndexedSeq[I] =
  Interface.declareVec(Source.capture, Interface.Role.Master, v)

/** R31 — the mirror image of `Master(Vec(n, i))`. */
inline def Slave[I <: Interface](v: InterfaceVec[I])(using m: Module): IndexedSeq[I] =
  Interface.declareVec(Source.capture, Interface.Role.Slave, v)

/** Typed Vec-of-Bundle ports preserve the concrete element Bundle subtype. */
inline def In[B <: Bundle](v: BundleVec[B])(using m: Module): BundleVecSignal[B] =
  new BundleVecSignal(m.builder.addPort(Source.capture, Direction.In, v.toType), v)

inline def Out[B <: Bundle](v: BundleVec[B])(using m: Module): BundleVecSignal[B] =
  new BundleVecSignal(m.builder.addPort(Source.capture, Direction.Out, v.toType), v)

/** R18.1 — Override a blackbox port's external emitted name, preserving case.
  * The same typed handle is returned so declarations remain fluent. */
extension (port: DslSignal)
  def named(externalName: String)(using m: Module): DslSignal =
    require(m.isInstanceOf[scalahdl.elab.BlackBox],
      "named is reserved for BlackBox ports")
    m.builder.renamePort(port.id, externalName)
    port.renameRoot(externalName)
    port

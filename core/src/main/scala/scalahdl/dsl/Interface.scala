package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.{Builder, Module}
import scalahdl.elab.Builder.ElabError
import scala.collection.mutable

/** R31 — A named group of ports whose directions are written once, from the
  * master's side.
  *
  * {{{
  *   /** A memory's client, written from the client's side. */
  *   final case class MemClient() extends Interface:
  *     val req = Master(Handshake(MemRequest()))   //  req_valid out, req_ready in, req_payload_* out
  *     val rsp = In(MemResponse())                 //  rsp_valid, rsp_data in
  *
  *   val mem    = Master(MemClient())   // in a client:   mem_req_valid, mem_req_ready, mem_req_payload_addr, …
  *   val client = Slave(MemClient())    // in the memory: the same names, every direction the other way
  *   port.mem <> memory.client          // every leaf, checked
  * }}}
  *
  * An `Interface` is to a module's ports what a `Bundle` (R07) is to a
  * signal's fields: a declaration whose fields are real Scala `val`s,
  * flattened by R07.1, and unknown to the IR. An endpoint *is* its ports, one
  * per leaf, named `<endpoint>_<field>`; nothing downstream of the builder
  * knows they belong together.
  *
  * What it adds over loose ports is that the directions are written once.
  * Inside the declaration `In` and `Out` are what the **master** sees, as
  * `UInt` inside a `Bundle` is a field rather than a wire. `Master(i)`
  * declares the ports as written and `Slave(i)` the mirror image, so each
  * leaf's direction is still one fact readable off the declaration — the
  * property R07 keeps for bundles by refusing per-field direction, kept here
  * by fixing the viewpoint instead.
  *
  * A field is a *blueprint* and binds to its handle — a `Type` to a signal,
  * a `Bundle` to itself with its typed fields, a `Vec` of bundles to its
  * typed handle, an encoding to its signal — through a [[Blueprint]]. A
  * nested interface is declared with `Master`/`Slave`, and its role composes
  * with the endpoint's: a slave's slave reads as written.
  *
  * Construction is the two phases a bundle's is. The field `val`s are made
  * *unbound* while the class body runs — a handle with a placeholder id, a
  * bundle not yet attached to anything — because the endpoint's name is the
  * `val` it is bound to, which is not known until the body has run. The
  * endpoint declaration then allocates one port per leaf, named and directed,
  * **onto** those handles (`Builder.addPort`'s `onto`), so the field's `val`
  * is the port's own handle and R23's instance-time rebinding reaches it.
  *
  * `<>` connects two endpoints of one shape leaf by leaf, from the side this
  * module reads to the side it drives — see [[Interface.connect]]. */
abstract class Interface:
  import Interface.*

  private val _fields = mutable.ListBuffer.empty[Field]
  private var _binding: Option[Binding] = None

  // ---- field spellers (R1: the name is the field `val`, via Source.capture)
  //
  // `Source.capture` is inlined directly into each speller, one level, so the
  // splice owner resolves to the user's field `val` — the same constraint
  // Bundle's spellers work under.

  /** `val valid = Out(Bool())` — a port the master drives. */
  protected inline def Out[P, H](p: => P)(using b: Blueprint[P, H]): H =
    leaf(Source.capture, Direction.Out, p)

  /** `val ready = In(Bool())` — a port the master receives. */
  protected inline def In[P, H](p: => P)(using b: Blueprint[P, H]): H =
    leaf(Source.capture, Direction.In, p)

  /** `val req = Master(Handshake(Word()))` — a nested interface, as written. */
  protected inline def Master[I <: Interface](i: => I): I =
    nested(Source.capture, Role.Master, i)

  /** `val rsp = Slave(Flow(Word()))` — a nested interface, mirrored. */
  protected inline def Slave[I <: Interface](i: => I): I =
    nested(Source.capture, Role.Slave, i)

  private def leaf[P, H](src: Source, written: Direction, p: => P)
                        (using b: Blueprint[P, H]): H =
    val name = fieldName(src)
    val blueprint = p
    val handle = b.unbound(blueprint, name)
    _fields += Leaf(name, written, b.typeOf(blueprint),
      allocate => b.bind(blueprint, handle, allocate))
    handle

  private def nested[I <: Interface](src: Source, role: Role, i: => I): I =
    val name = fieldName(src)
    val sub = i
    _fields += Nested(name, role, sub)
    sub

  private def fieldName(src: Source): String = src.name.getOrElse(
    throw ElabError(
      s"anonymous field of interface $kind has no name; bind it to a `val`", src.loc))

  /** The Scala class, for messages: `Handshake`, `MemClient`. */
  private[dsl] def kind: String = getClass.getSimpleName.nn.replace("$", "")

  /** Where this interface is declared as an endpoint, once it is. */
  private[dsl] def binding: Option[Binding] = _binding

  /** R31 — declare the ports: one per leaf, named `<base>_<field>`, directed
    * by the role, onto the handles the field `val`s already hold.
    *
    * Every port takes the **endpoint's** location, not the field's. A port
    * comes into existence where the design declares the endpoint, and that is
    * the line a diagnostic about it has to name; the field's own line is
    * inside the interface definition, which for `Handshake` is a file in the
    * library. The field is already named by the port (`s_ready`), so nothing
    * is lost. This is what `In(b)` does with a bundle too: one location, the
    * declaration's, for every leaf. */
  private[dsl] def bind(builder: Builder, base: String, role: Role, loc: Loc): Unit =
    _binding.foreach { first =>
      throw ElabError(
        s"interface $kind is already declared as `${first.base}` and cannot also be `$base`; " +
        "construct a fresh one for each endpoint", loc) }
    _binding = Some(Binding(builder, base, role))
    _fields.foreach {
      case l: Leaf =>
        val direction = role.direction(l.written)
        val root = l.allocate { (t, encoding, onto) =>
          builder.addPort(Source(Some(s"${base}_${l.name}"), loc), direction, t, encoding, onto) }
        l.bound = Some(Bound(root, direction))
      case n: Nested =>
        n.sub.bind(builder, s"${base}_${n.name}", role.compose(n.role), loc)
    }

  /** The declaration as data: names, written directions and types in order,
    * nested shapes with their written roles. Two endpoints connect when their
    * shapes are equal, whatever their roles. */
  private[dsl] def shape: Shape = _fields.toList.map {
    case l: Leaf => l.name -> Slot.Port(l.written, l.typeOf)
    case n: Nested => n.name -> Slot.Sub(n.role, n.sub.shape)
  }

  private[dsl] def fields: List[Field] = _fields.toList

  /** Connect this endpoint to another of the same shape. See
    * [[Interface.connect]] for what decides the direction of each leaf. */
  infix def <>(that: Interface)(using m: Module): Unit = Interface.connect(this, that)

object Interface:

  /** Which way an endpoint reads its declaration. */
  enum Role:
    case Master, Slave

    /** The direction a leaf written `written` has at an endpoint of this role. */
    def direction(written: Direction): Direction = this match
      case Master => written
      case Slave => written match
        case Direction.In => Direction.Out
        case Direction.Out => Direction.In

    /** A nested field's role seen from an endpoint of this role: mirrored twice
      * is as written. */
    def compose(inner: Role): Role = if this == inner then Master else Slave

  /** What a declaration says, with no endpoint in view. */
  type Shape = List[(String, Slot)]

  enum Slot:
    case Port(written: Direction, t: Type)
    case Sub(role: Role, shape: Shape)

  /** What a [[Blueprint]] needs from the port allocator: the port's type, its
    * encoding if it has one, and the handle to declare it onto. */
  type Allocate = (Type, Option[EncodingInfo], Option[DslSignal]) => DslSignal

  /** `Master(i)` / `Slave(i)` at module level: construct, then bind under the
    * `val`'s name. */
  private[dsl] def declare[I <: Interface](src: Source, role: Role, factory: => I)
                                          (using m: Module): I =
    val base = src.name.getOrElse(
      throw ElabError(
        s"anonymous endpoint has no name; bind `$role(...)` to a `val`", src.loc))
    val iface = factory
    iface.bind(m.builder, base, role, src.loc)
    iface

  /** `Master(Vec(n, i))` / `Slave(Vec(n, i))`: bind each element under the
    * `val`'s name and its index, which is the name R14.1 gives a port `Vec`'s
    * elements. Every element is an endpoint of its own from here on — nothing
    * downstream knows they were declared together, and `<>` connects one the
    * way it connects any other. */
  private[dsl] def declareVec[I <: Interface](src: Source, role: Role, v: InterfaceVec[I])
                                             (using m: Module): IndexedSeq[I] =
    val base = src.name.getOrElse(
      throw ElabError(
        s"anonymous endpoint has no name; bind `$role(...)` to a `val`", src.loc))
    v.elements.zipWithIndex.foreach { (iface, index) =>
      iface.bind(m.builder, s"${base}_$index", role, src.loc)
    }
    v.elements

  // ---- the record of a field

  private[dsl] sealed trait Field:
    def name: String

  private[dsl] final class Leaf(val name: String, val written: Direction, val typeOf: Type,
                                val allocate: Allocate => DslSignal) extends Field:
    var bound: Option[Bound] = None

  private[dsl] final class Nested(val name: String, val role: Role, val sub: Interface)
      extends Field

  private[dsl] final case class Bound(root: DslSignal, direction: Direction)

  private[dsl] final case class Binding(builder: Builder, base: String, role: Role)

  // ---- connection

  /** What a leaf is to the module connecting it. */
  private enum Side:
    /** An output of the module's own, or an input of a child: this module
      * drives it. */
    case Driven
    /** An input of the module's own, or an output of a child: something else
      * drives it and this module reads it. */
    case Read

    def phrase: String = this match
      case Driven => "driven here — an own output or a child's input"
      case Read => "read here — an own input or a child's output"

  /** R31 — connect two endpoints leaf by leaf.
    *
    * In the connecting module every leaf is one of two things: **driven
    * here** — an output of the module's own, or an input of a child, both of
    * which this module writes — or **read here** — an input of its own, or an
    * output of a child, both of which something else drives. Each pair of
    * leaves is connected from the one read here to the one driven here.
    *
    * The roles are what make that determinate, and every sensible pairing
    * comes out right without a case for it: a child's master and a child's
    * slave have one of each at every leaf; an own slave and a child's slave
    * forward, leaf for leaf, and so do a child's master and an own master; an
    * own slave and an own master pass straight through. Two masters, or two
    * slaves, on the same footing leave some leaf with two drivers or with
    * none, and that leaf is the error.
    *
    * The leaves are connected through `Assign.at`, the path `:=` takes, so
    * across an instance boundary each is a connection on the pin and inside a
    * module it is an assignment (R23). An aggregate leaf is connected whole,
    * as `u.req := producer` connects a bundle port: the builder puts one pin
    * per leaf on an instance (R23), a pass-through inside a module is one
    * unconditional assignment and so a continuous `assign` per leaf (R12.1),
    * and R14's shape check stays in force. */
  private[dsl] def connect(a: Interface, b: Interface)(using m: Module): Unit =
    val (ba, bb) = (endpoint(a), endpoint(b))
    if a.shape != b.shape then
      throw ElabError(
        s"cannot connect `${ba.base}` (${a.kind}) to `${bb.base}` (${b.kind}): shapes differ\n" +
        s"  `${ba.base}`: ${describe(a.shape)}\n" +
        s"  `${bb.base}`: ${describe(b.shape)}", Loc.NoLoc)
    connectFields(a, ba.base, b, bb.base)

  private def endpoint(i: Interface): Binding = i.binding.getOrElse(
    throw ElabError(
      s"interface ${i.kind} is not declared as an endpoint; declare it with `Master(...)` or " +
      "`Slave(...)` before connecting it", Loc.NoLoc))

  private def connectFields(a: Interface, baseA: String, b: Interface, baseB: String)
                           (using m: Module): Unit =
    a.fields.zip(b.fields).foreach {
      case (la: Leaf, lb: Leaf) =>
        val (ra, rb) = (la.bound.get, lb.bound.get)
        val (nameA, nameB) = (s"${baseA}_${la.name}", s"${baseB}_${lb.name}")
        (side(ra, nameA), side(rb, nameB)) match
          case (Side.Driven, Side.Read) => connectLeaf(ra.root, rb.root)
          case (Side.Read, Side.Driven) => connectLeaf(rb.root, ra.root)
          case (same, _) =>
            val consequence = same match
              case Side.Driven => "each would have two drivers"
              case Side.Read => "nothing drives either"
            throw ElabError(
              s"cannot connect `$baseA` to `$baseB`: `$nameA` and `$nameB` are both " +
              s"${same.phrase} — so $consequence. A connection pairs a leaf this module " +
              "drives with one it reads; two masters, or two slaves, on the same footing " +
              "have no such pair", Loc.NoLoc)
      case (na: Nested, nb: Nested) =>
        connectFields(na.sub, s"${baseA}_${na.name}", nb.sub, s"${baseB}_${nb.name}")
      case _ => () // unreachable: the shapes are equal
    }

  /** Which side a bound leaf is on, in the connecting module. A handle knows
    * whether it is an instance's input or output (R23); one that is neither
    * is a port of the module's own, and had better be — an endpoint of a
    * module that was constructed but never instantiated here would otherwise
    * read as the connecting module's own port of the same id. */
  private def side(bound: Bound, name: String)(using m: Module): Side =
    val h = bound.root
    if h.instanceInput.isDefined then Side.Driven
    else if h.instanceOutput.isDefined then Side.Read
    else
      if !h.owner.exists(_ eq m.builder) then
        throw ElabError(
          s"`$name` belongs to a module that is not instantiated here; instantiate it with " +
          "`inst(...)` and connect the instance's endpoint", Loc.NoLoc)
      bound.direction match
        case Direction.Out => Side.Driven
        case Direction.In => Side.Read

  /** Connect one leaf, whole — a scalar or an aggregate alike. */
  private def connectLeaf(target: DslSignal, source: DslSignal)(using m: Module): Unit =
    Assign.at(target, source.ref, Loc.NoLoc)

  private def describe(shape: Shape): String =
    shape.map {
      case (name, Slot.Port(written, t)) => s"$name: $written $t"
      case (name, Slot.Sub(role, sub)) => s"$name: $role ${describe(sub)}"
    }.mkString("[", ", ", "]")

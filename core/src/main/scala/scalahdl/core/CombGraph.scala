package scalahdl.core

import SignalId.*
import scala.collection.mutable

/** R28 / R29 — the combinational dependency graph.
  *
  * R17.1 says the IR graph is acyclic, and it is: a driver block references its
  * target and a signal never references its block. That is a statement about
  * the *object* graph. This is the other one — whose edges read *this value is
  * needed to compute that one* — and it is the graph a circuit either settles
  * on or does not. Its rationale even names the shape that separates them: "a
  * block's statements may reference the signal it drives (`y := y + 1`)". On a
  * register that is a counter; on a wire it is a ring.
  *
  * Derived, never stored: built after validation's earlier phases have passed
  * (so every literal has a width and `ExprType` answers rather than throws),
  * read by `CyclePhase` and by `LevelReport`, and discarded. Nothing here
  * rewrites the IR (R17).
  *
  * **Granularity is the leaf, and that is exact rather than a compromise.** R12
  * gives a bundle wire one driver block, so `r.addr := r.data + 1` would be a
  * self-edge on a signal-level graph and is not a ring in any circuit. Below
  * the leaf there is nothing to miss: R6.7 requires a register for a
  * part-select target and R14.2 requires one for a runtime-indexed element, so
  * every write to a *combinational* leaf writes the whole leaf. Reads stay
  * conservative in the other direction — a runtime index reads every element —
  * and that over-approximation does not over-report, because a wire really can
  * close a ring for the one value of the index that names it. */
object CombGraph:

  // ---------------------------------------------------------------- nodes

  /** A vertex. `Value` is the only kind with outgoing edges; the other three
    * are sinks, which is what makes a register break a ring without any
    * special case in the search — a clocked block's statements land on `Next`,
    * and `Next` goes nowhere. */
  enum Node:
    /** A combinational leaf's value; a register's or a synchronous memory
      * read's output; a module input. */
    case Value(id: SignalId, path: List[LeafSeg])
    /** A register's `D`. */
    case Next(id: SignalId, path: List[LeafSeg])
    /** One input pin of a child instance. */
    case Pin(instance: String, port: String)
    /** One memory write port. */
    case Write(memory: String, port: Int)

  /** What one step costs: `hops` counts edges between named signals, `cells`
    * weights the operators the step passed through (see [[Weight]]). Two
    * numbers rather than one because they answer different questions and the
    * weighted one is an estimate — R29 prints both and its model beside them. */
  final case class Cost(hops: Int, cells: Int):
    def +(other: Cost): Cost = Cost(hops + other.hops, cells + other.cells)
    def >(other: Cost): Boolean =
      cells > other.cells || (cells == other.cells && hops > other.hops)

  object Cost:
    val zero: Cost = Cost(0, 0)

  /** One dependency: `to` cannot be computed until `from` is. `via` names the
    * operators between them, for the report; `loc` is the statement that wrote
    * the dependency, which is what a diagnostic points at. */
  final case class Edge(from: Node, to: Node, cost: Cost, via: String, loc: Loc)

  /** What a parent needs to know about a child: which of its inputs reach
    * which of its outputs combinationally, and how deep the path inside is.
    * Carrying the depth rather than a boolean is what lets R29 measure a cone
    * that crosses hierarchy without flattening anything. */
  final case class ModuleSummary(name: String, paths: Map[(String, String), Cost])

  object ModuleSummary:
    def empty(name: String): ModuleSummary = ModuleSummary(name, Map.empty)

  // -------------------------------------------------------------- weights

  /** The cost model. Every number here is an estimate for *ranking*, printed
    * in R29's report so a reader can argue with it; none of them is a time.
    *
    * The consequential entry is [[select]]: an n-way selection is an n:1
    * multiplexer however Verilog spells it, and weighting it 1 would rank a
    * chain of `&` above a 749-arm microcode decode — which is the one finding
    * the report exists to make. */
  object Weight:
    /** ceil(log2 n) — the depth of a balanced tree over n things. */
    def clog2(n: Int): Int =
      if n <= 1 then 0 else 32 - Integer.numberOfLeadingZeros(n - 1)

    /** Wiring: a slice, a concatenation, a cast, a constant shift. No logic. */
    val wire: Int = 0
    /** One gate: bitwise, complement, a 2:1 mux. */
    val gate: Int = 1
    /** An adder, a comparator, a reduction — a tree over w bits. Modelled as
      * lookahead rather than ripple, which is what a modern fitter builds and
      * is the entry in this table most likely to be wrong on a family whose
      * carry chain is fast and narrow. */
    def tree(width: Int): Int = math.max(1, clog2(width))
    /** An n-way selection: a `switch`, a runtime `Vec` index, a barrel shift. */
    def select(n: Int): Int = math.max(1, clog2(n))
    /** Partial products plus their adder tree. */
    def multiply(width: Int): Int = 2 * math.max(1, clog2(width))
    /** An asynchronous read. Registers are a multiplexer built from logic; LUT
      * RAM is a hardened one, which is the whole reason to ask for it. */
    def asyncRead(kind: MemoryKind, depth: Int): Int = kind match
      case MemoryKind.Registers => select(depth)
      case _                    => gate

    /** The table as the report prints it. It lives here, beside the numbers it
      * describes, because a model the reader cannot see is a model the reader
      * cannot argue with — and these are estimates, so being argued with is
      * the point. */
    val model: List[(String, String)] = List(
      "0" -> "wiring: a slice, a field, a concatenation, a replication, a cast, a resize, a constant shift",
      "1" -> "one gate: bitwise, complement, a 2:1 multiplexer, a LUT-RAM asynchronous read",
      "ceil(log2 w)" -> "a w-bit adder, subtraction, comparison or reduction — modelled as lookahead, not ripple",
      "ceil(log2 n)" -> "an n-way selection: a switch over n arms, a runtime Vec index, a variable shift, a register-file read",
      "2*ceil(log2 w)" -> "a w-bit multiplier: partial products and their adder tree")

  // ---------------------------------------------------------------- graph

  final class Graph private[CombGraph] (
    val module: Module,
    val nodes: Vector[Node],
    private val outgoing: Map[Node, List[Edge]],
    /** Sinks in deterministic order — where a combinational cone ends. */
    val sinks: Vector[Node],
    /** What the graph could not know and assumed instead, for R29 to print. */
    val assumptions: List[String],
    private val signalName: SignalId => String,
    private val inputPorts: List[(String, SignalId)],
    private val outputPorts: List[(String, SignalId)]
  ):
    def out(node: Node): List[Edge] = outgoing.getOrElse(node, Nil)

    /** How a node reads in a message: `count`, `count.addr(3)`, `count (D)`. */
    def render(node: Node): String = node match
      case Node.Value(id, path) => s"${signalName(id)}${Leaf.render(path)}"
      case Node.Next(id, path)  => s"${signalName(id)}${Leaf.render(path)} (D)"
      case Node.Pin(inst, port) => s"$inst.$port"
      case Node.Write(mem, port) =>
        if port < 0 then s"$mem (write)" else s"$mem (write $port)"

    /** Every cycle reachable in the graph, each as the edges around the ring,
      * in a deterministic order. Empty is the normal answer. */
    lazy val cycles: List[List[Edge]] = findCycles(this)

    /** Longest paths from every source, in one pass over a topological order.
      * Meaningless on a cyclic graph, which is why R28 runs first. */
    lazy val depths: Map[Node, (Cost, Option[Edge])] = longestPaths(this, nodes)

    /** The chain of edges that achieves `depths(node)`, source first. */
    def pathTo(node: Node): List[Edge] =
      val out = mutable.ListBuffer.empty[Edge]
      var current = node
      var guard = 0
      var edge = depths.get(current).flatMap(_._2)
      while edge.isDefined && guard < nodes.length + 1 do
        out.prepend(edge.get)
        current = edge.get.from
        edge = depths.get(current).flatMap(_._2)
        guard += 1
      out.toList

    /** R29 — this module summarised for its parent: the depth of the deepest
      * combinational path from each input port to each output port it reaches. */
    lazy val summary: ModuleSummary =
      val paths = mutable.LinkedHashMap.empty[(String, String), Cost]
      inputPorts.foreach { case (inName, inId) =>
        val starts = nodes.collect { case v @ Node.Value(id, _) if id == inId => v: Node }
        if starts.nonEmpty then
          val reach = longestPaths(this, nodes, starts.toSet)
          outputPorts.foreach { case (outName, outId) =>
            val best = nodes.iterator.collect {
              case v @ Node.Value(id, _) if id == outId => reach.get(v).map(_._1)
            }.flatten.reduceOption((a, b) => if a > b then a else b)
            best.foreach(cost => paths((inName, outName)) = cost)
          }
      }
      ModuleSummary(module.name, paths.toMap)

  // ---------------------------------------------------------------- build

  /** Build the graph for one module. `summaries` answers for generated
    * children, keyed by emitted module name; an external child answers for
    * itself through R18's declaration, and one that declares nothing is
    * assumed to have no combinational path at all — recorded in
    * `assumptions`, because an assumption that decides a verdict must be
    * visible where the verdict is. */
  def build(module: Module, summaries: Map[String, ModuleSummary] = Map.empty): Graph =
    val signals = module.signals.iterator.map(s => s.id -> s).toMap
    val types: SignalId => Type = id => signals(id).typeOf
    val nameOf: SignalId => String = id => signals.get(id).map(_.name).getOrElse(s"<${id.value}>")
    val edges = mutable.ListBuffer.empty[Edge]
    val touched = mutable.LinkedHashSet.empty[Node]
    val assumptions = mutable.ListBuffer.empty[String]

    def note(node: Node): Node = { touched += node; node }

    /** Every leaf beneath `path` in `id`'s type — one node for a scalar. */
    def leavesUnder(id: SignalId, path: List[LeafSeg]): List[List[LeafSeg]] =
      signals.get(id) match
        case Some(signal) => Leaf.leaves(Leaf.typeAt(signal.typeOf, path)).map(path ++ _)
        case None         => List(path)

    def width(e: Expr): Int = ExprType.widthOpt(e, types).getOrElse(1)

    def vecLength(id: SignalId, path: List[LeafSeg]): Int =
      signals.get(id).map(s => Leaf.typeAt(s.typeOf, path)) match
        case Some(Type.Vec(n, _)) => n
        case _                    => 2

    /** Every leaf read by `e`, with the weight of the operators between that
      * read and the root of `e`. `above` is what has already been counted on
      * the way down; `via` names those operators for the report. */
    def reads(e: Expr, above: Int, via: List[String]): List[(Node, Int, List[String])] =
      val out = mutable.ListBuffer.empty[(Node, Int, List[String])]
      def walk(e: Expr, above: Int, via: List[String]): Unit =
        Leaf.readPath(e) match
          case Some((id, path)) =>
            leavesUnder(id, path).foreach(leaf =>
              out += ((note(Node.Value(id, leaf)), above, via)))
          case None => e match
            case Expr.Lit(_, _, _) => ()
            case Expr.Ref(_)       => ()
            case Expr.UnOp(op, arg) =>
              val cost =
                if ExprType.Op.isReduction(op) then Weight.tree(width(arg)) else Weight.gate
              walk(arg, above + cost, op :: via)
            case Expr.BinOp(op, lhs, rhs) =>
              val operand = math.max(width(lhs), width(rhs))
              val constantShift = ExprType.Op.isShift(op) && (rhs match
                case Expr.Lit(_, _, _) => true
                case _                 => false)
              val cost =
                if ExprType.Op.isBitwise(op) then Weight.gate
                else if ExprType.Op.isMultiply(op) then Weight.multiply(operand)
                else if constantShift then Weight.wire
                else if ExprType.Op.isShift(op) then Weight.select(math.max(2, width(lhs)))
                else Weight.tree(operand)
              val label = if constantShift then op else s"$op ${operand}b"
              walk(lhs, above + cost, label :: via)
              walk(rhs, above + cost, label :: via)
            case Expr.Mux(sel, whenTrue, whenFalse) =>
              walk(sel, above + Weight.gate, "mux" :: via)
              walk(whenTrue, above + Weight.gate, "mux" :: via)
              walk(whenFalse, above + Weight.gate, "mux" :: via)
            case Expr.Index(base, index) =>
              // A runtime index: an n:1 multiplexer reading every element.
              val cost = Leaf.readPath(base) match
                case Some((id, path)) => Weight.select(vecLength(id, path))
                case None             => Weight.gate
              walk(base, above + cost, "index" :: via)
              walk(index, above + cost, "index" :: via)
            case Expr.VarSlice(base, lo, _) =>
              // R34 — a window at a runtime offset is a select over the base's
              // bits, the same width of choice a runtime index makes.
              val cost = Weight.select(width(base))
              walk(base, above + cost, "varslice" :: via)
              walk(lo, above + cost, "varslice" :: via)
            case Expr.Slice(base, _, _)  => walk(base, above, via)
            case Expr.Field(base, _)     => walk(base, above, via)
            case Expr.Cat(parts)         => parts.foreach(walk(_, above, via))
            case Expr.Repeat(_, inner)   => walk(inner, above, via)
            case Expr.Resize(_, inner)   => walk(inner, above, via)
            case Expr.TypeCast(_, inner) => walk(inner, above, via)
            case Expr.Cdc(_, inner, _)   => walk(inner, above, via)
      walk(e, above, via)
      out.toList

    def addEdges(from: List[(Node, Int, List[String])], to: List[Node], loc: Loc): Unit =
      from.foreach { case (source, cost, via) =>
        to.foreach { target =>
          // A self-edge is kept deliberately: `y := y + 1` on a wire is the
          // one-node ring, and dropping it would hide the simplest cycle
          // there is.
          edges += Edge(source, note(target), Cost(1, cost), viaLabel(via), loc)
        }
      }

    /** The targets one assignment writes. A target whose path is not constant
      * writes the whole root — unreachable on a wire (R6.7/R14.2), and the
      * conservative answer on a register, where it costs nothing but breadth. */
    def targetsOf(lhs: Lhs, clocked: Boolean): List[Node] =
      val (id, path) = Leaf.path(lhs).getOrElse(lhs.rootId -> Nil)
      leavesUnder(id, path).map(leaf =>
        note(if clocked then Node.Next(id, leaf) else Node.Value(id, leaf)))

    /** Walk a driver block. `depth` is the weight of the multiplexers this
      * statement already sits under and `context` names them, so an edge whose
      * whole cost came from the conditionals around it says `mux` rather than
      * `wire`. `guards` are the conditions that built those multiplexers, each
      * carrying the depth and context at which it selects. */
    def walkStmts(stmts: List[Stmt], clocked: Boolean, depth: Int,
                  context: List[String], guards: List[(Expr, Int, List[String])]): Unit =
      def fromGuards(to: List[Node], loc: Loc): Unit =
        guards.foreach { case (cond, at, ctx) => addEdges(reads(cond, at, ctx), to, loc) }
      stmts.foreach {
        case Stmt.Assign(target, expr, _, loc) =>
          val to = targetsOf(target, clocked)
          addEdges(reads(expr, depth, context), to, loc)
          fromGuards(to, loc)
        case Stmt.When(cond, thenStmts, elseStmts, _) =>
          val inner = depth + Weight.gate
          val innerContext = "mux" :: context
          val nested = (cond, inner, innerContext) :: guards
          walkStmts(thenStmts, clocked, inner, innerContext, nested)
          walkStmts(elseStmts, clocked, inner, innerContext, nested)
        case Stmt.Switch(_, selector, cases, defaultStmts, _) =>
          val arms = cases.length + (if defaultStmts.isDefined then 1 else 0)
          val inner = depth + Weight.select(arms)
          val innerContext = s"case ${arms}-way" :: context
          // R26 — where the alternatives are conditions the labels are the
          // selection, so they are read exactly as the selector is.
          val labels = cases.flatMap(_.values).map(value => (value, inner, innerContext))
          val nested = (selector, inner, innerContext) :: labels ++ guards
          cases.foreach(alternative =>
            walkStmts(alternative.stmts, clocked, inner, innerContext, nested))
          defaultStmts.foreach(walkStmts(_, clocked, inner, innerContext, nested))
        case Stmt.Check(_, _, _, _) =>
          // R27/R28/R29 — **a check contributes no edge, and that is a
          // requirement rather than an omission.** These edges mean "this value
          // is needed to compute that one", and a check computes nothing: an
          // edge from one would let an assertion invent a combinational cycle
          // that no hardware has, or lengthen a path that no signal travels.
          // The claim a design makes about itself must not change the design it
          // is making the claim about. Unreachable as things stand — the graph
          // is built from driver blocks — and correct the day it is not.
          ()
        case Stmt.MemWrite(id, addr, data, loc) =>
          val to = List(note(Node.Write(nameOf(id), -1)))
          addEdges(reads(addr, depth, context), to, loc)
          addEdges(reads(data, depth, context), to, loc)
          fromGuards(to, loc)
      }

    // Driver blocks. A clocked block's edges land on `Next`, which has no
    // outgoing edge: that is how a register breaks a ring, with no special
    // case in the search, while still carrying the depth of the logic in
    // front of it for R29.
    module.drivers.foreach { block =>
      walkStmts(block.stmts, block.clocked, 0, Nil, Nil)
    }

    // R13 — a reset value is ordinary logic in a branch, so it feeds `D`
    // through the reset mux. A power-up value is a property of the bitstream
    // and is a constant (R13.8); it is not logic and contributes nothing.
    module.signals.filter(_.isReg).foreach { signal =>
      signal.init.foreach { init =>
        val to = leavesUnder(signal.id, Nil).map(leaf => note(Node.Next(signal.id, leaf)))
        addEdges(reads(init, Weight.gate, List("reset")), to, signal.loc)
      }
    }

    // R15 — an asynchronous read is a continuous array read and is an edge
    // from its address; a synchronous result is registered and is a source.
    module.memories.foreach { memory =>
      memory.reads.foreach { port =>
        if !port.synchronous then
          val cost = Weight.asyncRead(memory.kind, memory.depth)
          val to = leavesUnder(port.result, Nil).map(leaf => note(Node.Value(port.result, leaf)))
          addEdges(reads(port.address, cost, List(s"${memory.name} read")), to, port.loc)
      }
      memory.writes.zipWithIndex.foreach { case (port, index) =>
        val to = List(note(Node.Write(memory.name, index)))
        addEdges(reads(port.address, 0, Nil), to, port.loc)
        addEdges(reads(port.data, 0, Nil), to, port.loc)
        port.enable.foreach(enable => addEdges(reads(enable, 0, Nil), to, port.loc))
      }
    }

    // R23 — an instance is edges, not an opaque box. Each input pin is a sink
    // in its own right (a path that ends at a child's register ends there and
    // is still a path), and each declared or summarised input-to-output route
    // carries the cone on through.
    module.instances.foreach { instance =>
      val connection = instance.connections.toMap
      // R23 — an aggregate port may be connected pin by pin, in which case the
      // child's summary names a port this map does not have. Its sources are
      // the union of its pins', which is what a path *into* that port reads.
      def sourcesOf(port: String): List[Expr] =
        connection.get(port) match
          case Some(expr) => List(expr)
          case None       => instance.connections.collect {
            case (pin, expr) if pin == port || pin.startsWith(s"${port}_") => expr
          }
      val inputs = instance.connections.filterNot((port, _) => instance.outputs.contains(port))
      inputs.foreach { case (port, expr) =>
        addEdges(reads(expr, 0, Nil), List(note(Node.Pin(instance.name, port))), instance.loc)
      }
      val routes: Map[(String, String), Cost] =
        if instance.external then instance.combPaths.iterator.map(_ -> Cost(1, 1)).toMap
        else summaries.get(instance.target).map(_.paths).getOrElse(Map.empty)
      if instance.external && instance.combPaths.isEmpty then
        assumptions += s"external instance `${instance.name}` (${instance.target}) " +
          "declares no combinational path from any input to any output, which is the default assumption"
      else if !instance.external && !summaries.contains(instance.target) then
        assumptions += s"child instance `${instance.name}` (${instance.target}) " +
          "was not analysed; no combinational path through it is assumed"
      routes.toList.sortBy((pair, _) => (pair._1, pair._2)).foreach { case ((in, out), cost) =>
        for
          inExpr  <- sourcesOf(in)
          outExpr <- connection.get(out).toList
          (outId, outPath) <- Leaf.readPath(outExpr).toList
        do
          val to = leavesUnder(outId, outPath).map(leaf => note(Node.Value(outId, leaf)))
          val from = reads(inExpr, 0, Nil)
          from.foreach { case (source, within, via) =>
            to.foreach { target =>
              edges += Edge(source, target, Cost(cost.hops, within + cost.cells),
                viaLabel(s"${instance.name}.$in->$out" :: via), instance.loc)
            }
          }
      }
    }

    // A module port is a node whether or not anything in this module touches
    // it: an unread input is still a source and an undriven output is still an
    // endpoint, and a report that omitted them would be quietly incomplete.
    val portIds = module.ports.flatMap { port =>
      module.signals.find(_.name == port.name).map(port -> _)
    }
    portIds.foreach { case (_, signal) =>
      leavesUnder(signal.id, Nil).foreach(leaf => note(Node.Value(signal.id, leaf)))
    }

    val ordered = touched.toVector.sortBy(orderKey)
    val outgoing = edges.toList.groupBy(_.from).view.mapValues(_.distinct).toMap
    val outputIds = portIds.collect { case (p, s) if p.direction == Direction.Out => s.id }.toSet
    // Where a cone ends: a register's `D`, a child's input pin, a memory write
    // port, or a module output — the four things whose value is *used* by
    // something this graph does not model.
    val sinkNodes = ordered.filter {
      case Node.Value(id, _) => outputIds.contains(id)
      case _                 => true
    }
    new Graph(module, ordered, outgoing, sinkNodes, assumptions.toList.distinct, nameOf,
      portIds.collect { case (p, s) if p.direction == Direction.In => p.name -> s.id },
      portIds.collect { case (p, s) if p.direction == Direction.Out => p.name -> s.id })

  private def viaLabel(via: List[String]): String =
    val parts = via.filter(_.nonEmpty)
    if parts.isEmpty then "" else parts.reverse.distinct.mkString(", ")

  private def orderKey(node: Node): (Int, Int, String) = node match
    case Node.Value(id, path)  => (0, id.value, Leaf.render(path))
    case Node.Next(id, path)   => (1, id.value, Leaf.render(path))
    case Node.Pin(inst, port)  => (2, 0, s"$inst.$port")
    case Node.Write(mem, port) => (3, port, mem)

  // --------------------------------------------------------------- search

  /** Every cycle the graph contains, found by one depth-first walk with an
    * explicit stack — deep combinational cones are ordinary and a recursive
    * walk would be one design away from overflowing. A ring is reported once,
    * keyed by the nodes in it. */
  private def findCycles(graph: Graph): List[List[Edge]] =
    val White = 0; val Grey = 1; val Black = 2
    val colour = mutable.HashMap.empty[Node, Int].withDefaultValue(White)
    val found = mutable.LinkedHashMap.empty[Set[Node], List[Edge]]
    graph.nodes.foreach { start =>
      if colour(start) == White then
        val nodeStack = mutable.ArrayBuffer[Node](start)
        val edgeStack = mutable.ArrayBuffer.empty[Edge]
        val iterators = mutable.ArrayBuffer[Iterator[Edge]](graph.out(start).iterator)
        colour(start) = Grey
        while nodeStack.nonEmpty do
          val iterator = iterators.last
          if iterator.hasNext then
            val edge = iterator.next()
            colour(edge.to) match
              case White =>
                colour(edge.to) = Grey
                nodeStack += edge.to
                edgeStack += edge
                iterators += graph.out(edge.to).iterator
              case Grey =>
                val at = nodeStack.indexOf(edge.to)
                val ring = (if at >= 0 then edgeStack.drop(at).toList else Nil) :+ edge
                found.getOrElseUpdate(ring.map(_.to).toSet, ring)
              case _ => ()
          else
            colour(nodeStack.last) = Black
            nodeStack.remove(nodeStack.length - 1)
            iterators.remove(iterators.length - 1)
            if edgeStack.nonEmpty then edgeStack.remove(edgeStack.length - 1)
    }
    found.values.toList

  /** Longest path to every node, by cells then hops, with the edge that
    * achieved it. Relaxation over a topological order; `starts` restricts the
    * sources, which is how a per-input-port summary is computed. */
  private def longestPaths(graph: Graph, nodes: Vector[Node],
                           starts: Set[Node] = Set.empty): Map[Node, (Cost, Option[Edge])] =
    val order = topological(graph, nodes)
    val best = mutable.HashMap.empty[Node, (Cost, Option[Edge])]
    val restricted = starts.nonEmpty
    order.foreach { node =>
      if !restricted || starts.contains(node) then best.getOrElseUpdate(node, Cost.zero -> None)
    }
    order.foreach { node =>
      best.get(node).foreach { case (cost, _) =>
        graph.out(node).foreach { edge =>
          val candidate = cost + edge.cost
          val current = best.get(edge.to)
          if current.isEmpty || candidate > current.get._1 then
            best(edge.to) = candidate -> Some(edge)
        }
      }
    }
    best.toMap

  /** Kahn's algorithm over the node order, so the result is deterministic.
    * Nodes left in a cycle are appended in declaration order: this runs only
    * after R28 has passed, and appending rather than throwing keeps a report
    * possible on a graph a caller chose not to check. */
  private def topological(graph: Graph, nodes: Vector[Node]): Vector[Node] =
    val indegree = mutable.HashMap.empty[Node, Int].withDefaultValue(0)
    nodes.foreach(node => graph.out(node).foreach(edge => indegree(edge.to) += 1))
    val ready = mutable.Queue.empty[Node]
    nodes.foreach(node => if indegree(node) == 0 then ready.enqueue(node))
    val out = mutable.ArrayBuffer.empty[Node]
    val seen = mutable.HashSet.empty[Node]
    while ready.nonEmpty do
      val node = ready.dequeue()
      if seen.add(node) then
        out += node
        graph.out(node).foreach { edge =>
          indegree(edge.to) -= 1
          if indegree(edge.to) == 0 then ready.enqueue(edge.to)
        }
    nodes.foreach(node => if !seen.contains(node) then out += node)
    out.toVector

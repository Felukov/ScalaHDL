package scalahdl.hierarchy

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveLow
import scalahdl.core.{Direction, Type}
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, param}
import scalahdl.emit.Emission

/** R23 — Hierarchical port connection. A parent connects a generated child's
  * data inputs, reads its outputs from `<instance>_<port>` nets, and gets a
  * named diagnostic for each of the three ways the connection can be wrong.
  *
  * Each test maps to a checkbox in `roadmap/open/M15-hierarchy/R23-hierarchical-ports.md`. */
final class HierarchicalPortSuite extends munit.FunSuite:

  /** Ports are declared out of assignment order on purpose: `sum` sits between
    * the two inputs, so a connection list that merely appended in assignment
    * order would be visibly different from the declaration order R23 requires. */
  class Adder(width: Int) extends Module(param("w", width)):
    val a = In(UInt(width))
    val sum = Out(UInt(width))
    val b = In(UInt(width))
    given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
    sum := a + b

  class Accumulator extends Module:
    val clk = In(Clock())
    val resetn = In(Reset(ActiveLow))
    val step = In(UInt(8))
    val total = Out(UInt(8))
    given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)

    val adder = inst(new Adder(8))
    val acc = Reg(UInt(8), init = 0.U(8))
    adder.b := step
    adder.a := acc
    acc := adder.sum
    total := acc

  private def emit(top: Module): String =
    new Emission().emit(top.elaborateDesign()).files.last._2

  private def failure(body: => Any): ValidationFailed =
    intercept[ValidationFailed](body)

  test("a parent connects a child's data inputs and reads its output"):
    val verilog = emit(new Accumulator)
    assert(verilog.contains("wire [7:0] adder_sum;"), verilog)
    assert(verilog.contains(".a(acc)"), verilog)
    assert(verilog.contains(".b(step)"), verilog)
    assert(verilog.contains(".sum(adder_sum)"), verilog)
    assert(verilog.contains("acc <= adder_sum;"), verilog)

  test("connections emit in the child's port declaration order"):
    val verilog = emit(new Accumulator)
    val instantiation = verilog.linesIterator
      .dropWhile(!_.contains("Adder_w8 adder")).takeWhile(!_.contains(");")).toList
    val ports = instantiation.collect { case line if line.contains(".") =>
      line.trim.takeWhile(_ != '(').drop(1)
    }
    // Declaration order is a, sum, b — deliberately not the order the parent
    // assigned them (b before a), and not alphabetical.
    assertEquals(ports, List("a", "sum", "b"))

  test("the output net is declared once and driven only by the instance"):
    val design = (new Accumulator).elaborateDesign()
    val nets = design.top.signals.filter(_.name == "adder_sum")
    assertEquals(nets.length, 1)
    assert(!design.top.drivers.exists(_.signalId == nets.head.id),
      "an instance-driven net must have no driver block (R12)")
    assert(!design.top.ports.exists(_.name == "adder_sum"))

  test("a child that is itself a parent elaborates and emits one file per module"):
    class Middle extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val leaf = inst(new Adder(8))
      leaf.a := x
      leaf.b := 1.U(8)
      y := leaf.sum

    class Top extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val mid = inst(new Middle)
      mid.x := x
      y := mid.y

    val emitted = new Emission().emit((new Top).elaborateDesign())
    assertEquals(emitted.files.map(_._1), List("Adder_w8.v", "Middle.v", "Top.v"))
    val top = emitted.files.last._2
    assert(top.contains("wire [7:0] mid_y;"), top)
    assert(top.contains(".x(x)"), top)
    assert(top.contains(".y(mid_y)"), top)

  /** Regression: `Child u;` is a *variable declaration* of an unknown type, not
    * an instantiation. It parsed here and was rejected downstream with "can't
    * find typedef", which the hierarchy lint found once whole designs were
    * linted rather than one file at a time. */
  test("a port-less instance emits an instantiation, not a variable declaration"):
    class Empty extends Module
    class HasEmpty extends Module:
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val sub = inst(new Empty)

    val verilog = emit(new HasEmpty)
    assert(verilog.contains("Empty sub();"), verilog)

  test("an explicit clock connection replaces the inherited one"):
    class TwoClocks extends Module:
      val clk = In(Clock())
      val otherClk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val step = In(UInt(8))
      val total = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val counter = inst(new Accumulator)
      counter.clk := otherClk
      counter.step := step
      total := counter.total

    val design = (new TwoClocks).elaborateDesign()
    val connections = design.top.instances.head.connections
    assertEquals(connections.count(_._1 == "clk"), 1,
      s"an override must replace, not duplicate: $connections")
    val otherClkId = design.top.signals.find(_.name == "otherClk").map(_.id)
    assertEquals(connections.toMap.get("clk"), otherClkId.map(scalahdl.core.Expr.Ref(_)))
    assertEquals(connections.map(_._1), List("clk", "resetn", "step", "total"))

  test("an unconnected data input is an error naming the instance and port"):
    class ForgetsAnInput extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val adder = inst(new Adder(8))
      adder.a := x
      y := adder.sum

    val error = failure((new ForgetsAnInput).elaborateDesign())
    assertEquals(error.diagnostics.map(_.code), List(Code.INSTANCE_INPUT_UNCONNECTED))
    val summary = error.diagnostics.head.summary
    assert(summary.contains("`b`") && summary.contains("`adder`") && summary.contains("Adder_w8"),
      summary)
    assert(error.diagnostics.head.fix.exists(_.contains("adder.b :=")),
      error.diagnostics.head.fix.toString)

  test("assigning a child's output port is an error naming the driving instance"):
    class DrivesAnOutput extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val x = In(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val adder = inst(new Adder(8))
      adder.a := x
      adder.b := x
      adder.sum := x

    val error = failure(new DrivesAnOutput)
    assertEquals(error.diagnostics.map(_.code), List(Code.INSTANCE_OUTPUT_ASSIGNED))
    assert(error.diagnostics.head.summary.contains("`adder`"),
      error.diagnostics.head.summary)

  /** Found by the first design to use R23: a connection written inside a `when`
    * looked conditional and emitted unconditionally, *and* being last-wins it
    * replaced what the code above the `when` had connected. */
  test("connecting an instance input inside a conditional scope is an error"):
    class ConnectsInsideWhen extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val x = In(UInt(8))
      val pick = In(Bool())
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val adder = inst(new Adder(8))
      adder.a := x
      adder.b := x
      when(pick) {
        adder.b := 1.U(8)
      }

    val error = failure(new ConnectsInsideWhen)
    assertEquals(error.diagnostics.map(_.code), List(Code.INSTANCE_CONNECT_IN_SCOPE))
    assert(error.diagnostics.head.fix.exists(_.contains("Wire")),
      error.diagnostics.head.fix.toString)

  /** The regression for the failure that produced M15: before R23 this recorded
    * an assignment in the parent's driver table under the *child's* signal id,
    * silently driving whichever parent signal shared it. */
  test("assigning a signal of a module that was never instantiated is an error"):
    class WritesAForeignSignal extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val stranger = new Adder(8)   // constructed, never `inst`-ed
      stranger.a := x
      y := x

    val error = failure(new WritesAForeignSignal)
    assertEquals(error.diagnostics.map(_.code), List(Code.FOREIGN_SIGNAL_ASSIGNED))
    assert(error.diagnostics.head.fix.exists(_.contains("inst(")),
      error.diagnostics.head.fix.toString)

  /** R23 — and the same hazard on the read side, which is the worse half.
    *
    * An instance input is never rebound to a parent net: `:=` on it records a
    * connection rather than an assignment. So a *read* built `Expr.Ref` of the
    * child's id inside the parent, where per-module ids made it name whichever
    * local signal shared the number — the child's second port read back as the
    * parent's second port. A wrong assignment has some chance of being caught
    * by a completeness or multiple-driver rule; a wrong read is ordinary logic
    * and elaborates, emits and simulates without a word. */
  test("reading an instance's input port is an error, not the value driving it"):
    class ReadsAnInstanceInput extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val p = In(UInt(8))
      val q = In(UInt(8))
      val z = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val u = inst(new Adder(8))
      u.a := p
      u.b := q
      z := u.a          // the child's `a`, which this module drives

    val error = failure(new ReadsAnInstanceInput)
    assertEquals(error.diagnostics.map(_.code), List(Code.INSTANCE_INPUT_READ))

  test("an aggregate port connects whole, and an output's leaves follow its root"):
    class Pair extends Bundle:
      val lo = UInt(8)
      val hi = UInt(8)

    class Widen extends Module:
      val in = In(new Pair)
      val out = Out(new Pair)
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      out.lo := in.lo
      out.hi := in.hi

    class WidenTop extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val value = In(new Pair)
      val total = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val widen = inst(new Widen)
      widen.in := value
      // Reading a *field* of the child's output is the case that used to carry
      // the child's signal id: `widen_out_lo` is the parent net's leaf.
      total := widen.out.lo + widen.out.hi

    val design = (new WidenTop).elaborateDesign()
    val verilog = new Emission().emit(design).files.last._2
    // R23 connects the aggregate port whole; R07.1 decides how that reads in
    // Verilog-2001, which has no aggregate port to connect to — both sides are
    // already flattened, so the instantiation names leaf against leaf.
    assert(verilog.contains(".in_lo(value_lo)") && verilog.contains(".in_hi(value_hi)"),
      verilog)
    assert(verilog.contains(".out_lo(widen_out_lo)") && verilog.contains(".out_hi(widen_out_hi)"),
      verilog)
    assert(verilog.contains("widen_out_lo") && verilog.contains("widen_out_hi"), verilog)
    assert(!verilog.contains("value_lo + value_hi"),
      s"the child's output must not read back as the parent's input: $verilog")

  test("assigning a field of a child's output port is rejected"):
    class Pair extends Bundle:
      val lo = UInt(8)
      val hi = UInt(8)

    class Source extends Module:
      val out = Out(new Pair)
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      out.lo := 0.U(8)
      out.hi := 0.U(8)

    class DrivesAField extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val x = In(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val source = inst(new Source)
      source.out.lo := x

    val error = failure(new DrivesAField)
    assertEquals(error.diagnostics.map(_.code), List(Code.INSTANCE_OUTPUT_ASSIGNED))

  /** R23 — "a field of an *input* bundle belongs to the child and is not an
    * assignment target in the parent: connect the whole port." Only the port's
    * root is bound as an instance input, so a field assignment is not a
    * narrower connection — it is a write into the child's module. */
  // ---- R23, M42: an aggregate input connects whole *or* pin by pin
  //
  // The boundary has one pin per leaf, so a leaf is a pin and connecting one is
  // the same kind of thing as connecting a scalar port: the expression lands on
  // the pin, with no net invented in between.

  class Pair extends Bundle:
    val lo = UInt(8)
    val hi = UInt(8)

  /** `mid` sits between the two aggregates so a leaf connection that merely
    * appended would be visibly out of the child's declaration order. */
  class Sink extends Module:
    val in = In(new Pair)
    val mid = In(UInt(8))
    val lanes = In(Vec(2, UInt(8)))
    val out = Out(UInt(8))
    given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
    out := in.lo + in.hi + mid + lanes(0) + lanes(1)

  test("a parent connects one leaf of a child's aggregate input"):
    class ConnectsLeaves extends Module:
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val sink = inst(new Sink)
      sink.in.lo := x
      sink.in.hi := x + 1.U(8)
      sink.mid := x
      sink.lanes(0) := x
      sink.lanes(1) := x
      y := sink.out

    val verilog = emit(new ConnectsLeaves)
    assert(verilog.contains(".in_lo(x)"), verilog)
    assert(verilog.contains(".in_hi((x + 8'd1))"), verilog)
    assert(verilog.contains(".lanes_0(x)"), verilog)
    assert(verilog.contains(".lanes_1(x)"), verilog)
    // The expression is on the pin: no net stands between the two, exactly as
    // for the scalar port beside them.
    assert(!verilog.contains("wire [7:0] sink_in"), verilog)

  test("leaf connections keep the child's port declaration order"):
    class OutOfOrder extends Module:
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val sink = inst(new Sink)
      sink.lanes(1) := x            // written last-to-first on purpose
      sink.lanes(0) := x
      sink.mid := x
      sink.in.hi := x
      sink.in.lo := x
      y := sink.out

    val verilog = emit(new OutOfOrder)
    val pins = verilog.linesIterator
      .dropWhile(!_.contains("Sink sink")).takeWhile(!_.contains(");"))
      .collect { case l if l.contains(".") => l.trim.takeWhile(_ != '(') }.toList
    assertEquals(pins, List(".in_lo", ".in_hi", ".mid", ".lanes_0", ".lanes_1", ".out"))

  test("a port connected whole keeps the pins a later leaf does not name"):
    class WholeThenOverride extends Module:
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val both = Wire(new Pair)
      both.lo := x
      both.hi := x
      val sink = inst(new Sink)
      sink.in := both
      sink.in.hi := 0.U(8)          // one pin overridden; `lo` keeps the whole
      sink.mid := x
      sink.lanes(0) := x
      sink.lanes(1) := x
      y := sink.out

    val verilog = emit(new WholeThenOverride)
    assert(verilog.contains(".in_lo(both_lo)"), verilog)
    assert(verilog.contains(".in_hi(8'd0)"), verilog)

  test("...and connecting the whole port afterwards supersedes the pins"):
    class LeafThenWhole extends Module:
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val both = Wire(new Pair)
      both.lo := x
      both.hi := x
      val sink = inst(new Sink)
      sink.in.hi := 0.U(8)
      sink.in := both
      sink.mid := x
      sink.lanes(0) := x
      sink.lanes(1) := x
      y := sink.out

    val verilog = emit(new LeafThenWhole)
    assert(verilog.contains(".in_hi(both_hi)"), verilog)
    assert(!verilog.contains(".in_hi(8'd0)"), verilog)

  test("an aggregate input with some pins unconnected names the pins"):
    class Partial extends Module:
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val sink = inst(new Sink)
      sink.in.lo := x               // `in_hi` never connected
      sink.mid := x
      sink.lanes(0) := x
      sink.lanes(1) := x
      y := sink.out

    val error = failure((new Partial).elaborateDesign())
    assertEquals(error.diagnostics.map(_.code), List(Code.INSTANCE_INPUT_UNCONNECTED))
    assert(error.diagnostics.head.summary.contains("in_hi"),
      error.diagnostics.head.summary)
    assert(error.diagnostics.head.summary.contains("1 of the 2 pins"),
      error.diagnostics.head.summary)

  test("a window of a pin is not a connection"):
    class ConnectsAWindow extends Module:
      val x = In(UInt(8))
      val y = Out(UInt(8))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val sink = inst(new Sink)
      sink.mid(3, 0) := x(3, 0)
      y := sink.out

    val error = intercept[IllegalArgumentException](new ConnectsAWindow)
    assert(error.getMessage.contains("window of a pin"), error.getMessage)

  /** R08 across a hierarchy boundary, which is what M41 fixed for a scalar
    * payload and this completes: `<>` reaches a bundle payload's leaves, and
    * every one of them has to become a connection on the pin it names. The
    * shape is R31's forwarding — an own slave handed to a child's slave, leaf
    * for leaf: the inputs go down to the pins, the ready comes back up. */
  test("a handshake with a bundle payload connects across an instance"):
    class Consumer extends Module:
      val s = Slave(Handshake(new Pair))
      val out = Out(UInt(8))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      s.ready := True
      out := Mux(s.valid, s.payload.lo + s.payload.hi, 0.U(8))

    class Wrapper extends Module:
      val s = Slave(Handshake(new Pair))
      val y = Out(UInt(8))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val consumer = inst(new Consumer)
      s <> consumer.s
      y := consumer.out

    val verilog = emit(new Wrapper)
    assert(verilog.contains(".s_valid(s_valid)"), verilog)
    assert(verilog.contains(".s_payload_lo(s_payload_lo)"), verilog)
    assert(verilog.contains(".s_payload_hi(s_payload_hi)"), verilog)
    assert(verilog.contains("assign s_ready = consumer_s_ready;"), verilog)

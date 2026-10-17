package scalahdl.lib

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.core.{CombGraph, Direction, Type}
import scalahdl.emit.{Emission, VerilogEmitter}

/** R30 — what a design may rely on when it imports `scalahdl.lib.Skid`.
  *
  * The behaviour is the SystemVerilog testbench's business
  * (`core/src/test/verilog/tb_skid.sv`, run by `tools/lib-sim.sh`); what is
  * here is the shape: the R8 port names a parent connects to, the two
  * guarantees about where the registers are, and the module identity a
  * hierarchy of these deduplicates on. */
final class SkidSuite extends munit.FunSuite:

  private def emitted(skid: Skid): String =
    val design = skid.elaborateDesign()
    new Emission("0.1.0-SNAPSHOT").emit(design).files.head._2

  test("the ports are R8's, named for the endpoints they belong to"):
    val module = (new Skid(8)).elaborate()

    assertEquals(module.name, "Skid_w8")
    assertEquals(module.ports.map(p => (p.name, p.direction, p.typeOf)), List(
      ("clk", Direction.In, Type.Clock),
      ("rst", Direction.In, Type.Reset(ActiveLow)),
      ("enable", Direction.In, Type.Bool),
      ("clear", Direction.In, Type.Bool),
      ("s_valid", Direction.In, Type.Bool),
      ("s_ready", Direction.Out, Type.Bool),
      ("s_payload", Direction.In, Type.UInt(8)),
      ("m_valid", Direction.Out, Type.Bool),
      ("m_ready", Direction.In, Type.Bool),
      ("m_payload", Direction.Out, Type.UInt(8)),
      ("empty", Direction.Out, Type.Bool)))

  test("it elaborates and validates: `elaborate` is R11 and R28 running"):
    // Both phases throw rather than report, so reaching the assertion is the
    // result. R11 is the one with something to say here: five outputs, four
    // driver blocks and a `when(enable)` that assigns nothing on the else arm.
    val module = (new Skid(8)).elaborate()
    assertEquals(module.drivers.length, 9)
    assert(CombGraph.build(module).cycles.isEmpty)

  test("m_valid and m_payload are flop outputs"):
    val v = emitted(new Skid(8))
    // The out stage is a register and the port carries it out unchanged: one
    // net between the flop's Q and the pin, and no logic on it (R12.1).
    assert(v.contains("reg out_valid;"), v)
    assert(v.contains("reg [7:0] out_payload;"), v)
    assert(v.contains("assign m_valid = out_valid;"), v)
    assert(v.contains("assign m_payload = out_payload;"), v)
    // Every mux is on the D side, inside the clocked block.
    assert(v.contains("always @(posedge clk) begin"), v)

  test("s_ready is a flop read and m_ready reaches nothing combinational"):
    val module = (new Skid(8)).elaborate()
    val graph = CombGraph.build(module)

    def reachableFrom(port: String): Set[String] =
      val start = graph.nodes.find(graph.render(_) == port)
        .getOrElse(fail(s"no node named $port in the graph"))
      def walk(frontier: List[CombGraph.Node], seen: Set[CombGraph.Node]): Set[CombGraph.Node] =
        frontier match
          case Nil => seen
          case node :: rest =>
            val next = graph.out(node).map(_.to).filterNot(seen.contains)
            walk(next ++ rest, seen ++ next)
      walk(List(start), Set(start)).map(graph.render)

    // `m_ready` does reach logic — it is half of `out_load`, which is what
    // decides both D sides — so the traversal is doing something.
    val fromMReady = reachableFrom("m_ready")
    assert(fromMReady.contains("out_load"), fromMReady.toString)
    assert(fromMReady.contains("out_valid (D)"), fromMReady.toString)
    // ...and it stops there. No ready chain: `s_ready` is `slot_valid`
    // inverted, and a producer's back-pressure never sees the consumer's.
    assert(!fromMReady.contains("s_ready"), fromMReady.toString)
    assertEquals(reachableFrom("slot_valid"),
      Set("slot_valid", "s_ready", "empty", "slot_payload (D)", "out_valid (D)",
          "out_payload (D)"))

  test("the reset shape names the module only when it is not the default"):
    assertEquals((new Skid(8)).moduleName, "Skid_w8")
    assertEquals((new Skid(8, ResetKind.Async, ActiveLow)).moduleName, "Skid_w8")
    assertEquals((new Skid(8, ResetKind.Sync, ActiveHigh)).moduleName, "Skid_w8_rst_sync_high")
    assertEquals((new Skid(16, ResetKind.Async, ActiveHigh)).moduleName, "Skid_w16_rst_async_high")

    val sync = emitted(new Skid(8, ResetKind.Sync, ActiveHigh))
    assert(sync.contains("always @(posedge clk) begin"), sync)
    assert(!sync.contains("negedge rst"), sync)
    assert(sync.contains("if (rst) begin\n      slot_valid <= 1'b0;\n    end"), sync)

  test("a width below one bit is refused at construction"):
    val error = intercept[IllegalArgumentException](new Skid(0))
    assert(error.getMessage.nn.contains("at least one bit"), error.getMessage)

  test("the slice states its own invariant, and it costs the circuit nothing"):
    // R27/R30 — the reference's `assert property (tmp_vld |-> m_valid)`. It
    // ports across as an *immediate* check because both signals are flop
    // outputs: the block reads them at the edge, before this cycle's
    // non-blocking updates land, which are the values a concurrent assertion
    // samples in its preponed region. `disable iff (reset)` is the domain's
    // reset guard and is not restated (R13).
    val module = (new Skid(8)).elaborate()
    assertEquals(module.checks.length, 1)
    assertEquals(module.checks.head.stmts.length, 1)

    val v = emitted(new Skid(8))
    val region = v.linesIterator.toList
      .dropWhile(!_.contains("translate_off")).takeWhile(!_.contains("translate_on"))
    assert(region.exists(_.contains("always @(posedge clk) begin")), v)
    assert(region.exists(_.contains("if (rst) begin")), v)
    assert(region.exists(_.contains("!(((~slot_valid) | out_valid))")), v)
    assert(region.exists(_.contains("$stop;")), v)

    // The claim is not part of the slice. Nine driver blocks before it and
    // nine after; no signal became a `reg` because something read it in a
    // check, and R28's graph is the same graph.
    assertEquals(module.drivers.length, 9)
    assert(!module.signals.exists(_.id == module.checks.head.id))
    assert(CombGraph.build(module).cycles.isEmpty)

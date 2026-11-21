package scalahdl.lib

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.core.{CombGraph, Direction, MemoryKind, Type}
import scalahdl.emit.{Emission, Vendor}

/** R32 — what a design may rely on when it imports `scalahdl.lib.SyncFifo`.
  *
  * The behaviour is the SystemVerilog testbench's business
  * (`core/src/test/verilog/tb_sync_fifo.sv`, run by `tools/lib-sim.sh`); what
  * is here is the shape: the R8 port names, where the combinational paths go
  * and do not, the module's name, and what is refused. */
final class SyncFifoSuite extends munit.FunSuite:

  private def emitted(fifo: SyncFifo): String =
    val design = fifo.elaborateDesign()
    new Emission("0.1.2", Some(Vendor.Xilinx)).emit(design).files.head._2

  test("the ports are R8's, named for the endpoints they belong to"):
    val module = (new SyncFifo(8, 8)).elaborate()

    assertEquals(module.name, "SyncFifo_d8_w8")
    assertEquals(module.ports.map(p => (p.name, p.direction, p.typeOf)), List(
      ("clk", Direction.In, Type.Clock),
      ("rst", Direction.In, Type.Reset(ActiveLow)),
      ("s_valid", Direction.In, Type.Bool),
      ("s_ready", Direction.Out, Type.Bool),
      ("s_payload", Direction.In, Type.UInt(8)),
      ("m_valid", Direction.Out, Type.Bool),
      ("m_ready", Direction.In, Type.Bool),
      ("m_payload", Direction.Out, Type.UInt(8))))

  test("it elaborates and validates, with no combinational loop"):
    val module = (new SyncFifo(8, 8)).elaborate()
    assert(CombGraph.build(module).cycles.isEmpty)

  test("m_ready reaches only the head, and s_valid only the tail and the words"):
    val module = (new SyncFifo(8, 8)).elaborate()
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

    // No ready chain: a consumer's `ready` moves the head and nothing else,
    // and never the producer's `ready`.
    val fromMReady = reachableFrom("m_ready")
    assert(fromMReady.contains("head (D)"), fromMReady.toString)
    assert(!fromMReady.contains("s_ready"), fromMReady.toString)
    // Nothing passes from `s` to `m` on one clock: a word is offered from the
    // clock after it is taken.
    val fromSValid = reachableFrom("s_valid")
    assert(fromSValid.contains("tail (D)"), fromSValid.toString)
    assert(!fromSValid.contains("m_valid"), fromSValid.toString)
    assert(!fromSValid.contains("m_payload"), fromSValid.toString)
    assert(!fromSValid.contains("s_ready"), fromSValid.toString)

  test("depth and width name the module; storage and the reset shape only when not the default"):
    assertEquals((new SyncFifo(8, 10)).moduleName, "SyncFifo_d8_w10")
    assertEquals((new SyncFifo(8, 10, MemoryKind.LutRam)).moduleName,
      "SyncFifo_d8_w10_storage_lutram")
    assertEquals((new SyncFifo(8, 10, MemoryKind.Registers, ResetKind.Sync, ActiveHigh)).moduleName,
      "SyncFifo_d8_w10_rst_sync_high")

    val sync = emitted(new SyncFifo(8, 10, MemoryKind.Registers, ResetKind.Sync, ActiveHigh))
    assert(!sync.contains("negedge rst"), sync)

  test("the words are the storage asked for"):
    assert(emitted(new SyncFifo(8, 8)).contains("(* ram_style = \"registers\" *)"))
    assert(emitted(new SyncFifo(8, 8, MemoryKind.LutRam)).contains("(* ram_style = \"distributed\" *)"))

  test("a depth that is not a power of two, a width below one bit, and a block RAM are refused"):
    for depth <- List(0, 1, 3, 12) do
      val error = intercept[IllegalArgumentException](new SyncFifo(depth, 8))
      assert(error.getMessage.nn.contains("a power of two, 2 or more"), error.getMessage)
    val narrow = intercept[IllegalArgumentException](new SyncFifo(8, 0))
    assert(narrow.getMessage.nn.contains("at least one bit"), narrow.getMessage)
    val block = intercept[IllegalArgumentException](new SyncFifo(8, 8, MemoryKind.BlockRam))
    assert(block.getMessage.nn.contains("read a clock late"), block.getMessage)

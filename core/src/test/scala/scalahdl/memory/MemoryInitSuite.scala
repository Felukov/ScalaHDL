package scalahdl.memory

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.VerilogEmitter
import scalahdl.core.{MemoryInit, MemoryKind, ResetPolarity}
import scalahdl.clock.ResetKind

/** R15.4 — deterministic initial contents. The image is named rather than
  * embedded, so what these tests pin is the one emitted line, where it sits
  * relative to the declaration and the always blocks, and the fact that nothing
  * else about a memory moves when an image is added.
  */
class MemoryInitSuite extends munit.FunSuite:

  abstract class Clocked extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ResetPolarity.ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ResetPolarity.ActiveHigh)

  private def emit(m: Module): String =
    val design = m.elaborateDesign()
    new VerilogEmitter("0.1.2").emitModule(design.top, design.top.name)

  // -- the IR carries the image ------------------------------------------

  test("every kind accepts an image and carries it on the IR"):
    class Top extends Clocked:
      val addr = In(UInt(8))
      val regs = Registers(256, UInt(8), HexFile("regs.hex"))
      val lut = LutRam(256, UInt(8), HexFile("lut.hex"))
      val block = BlockRam(256, UInt(8), HexFile("block.hex"))
      val a = regs.readAsync(addr, ReadFirst)
      val b = lut.readAsync(addr, ReadFirst)
      val c = block.readSync(addr, ReadFirst)

    val memories = (new Top).elaborate().memories
    assertEquals(memories.map(_.kind),
      List(MemoryKind.Registers, MemoryKind.LutRam, MemoryKind.BlockRam))
    assertEquals(memories.map(_.init), List(
      Some(MemoryInit.HexFile("regs.hex")),
      Some(MemoryInit.HexFile("lut.hex")),
      Some(MemoryInit.HexFile("block.hex"))))

  test("an uninitialized memory carries no image"):
    class Top extends Clocked:
      val addr = In(UInt(8))
      val mem = BlockRam(256, UInt(8))
      val q = mem.readSync(addr, ReadFirst)

    assertEquals((new Top).elaborate().memories.head.init, None)

  // -- emission ------------------------------------------------------------

  test("the image emits one initial load directly under its declaration"):
    class Top extends Clocked:
      val addr = In(UInt(8))
      val data = In(UInt(8))
      val enable = In(Bool())
      val mem = BlockRam(256, UInt(8), HexFile("image.hex"))
      val q = mem.readSync(addr, ReadFirst)
      mem.write(addr, data, enable)

    val v = emit(new Top)
    assert(v.contains("""  initial $readmemh("image.hex", mem);"""), v)

    // Declaration, then load, then the read net: the load belongs to the array.
    val lines = v.linesIterator.toList
    val declaration = lines.indexWhere(_.contains("reg [7:0] mem [0:255];"))
    val load = lines.indexWhere(_.contains("$readmemh"))
    val readNet = lines.indexWhere(_.contains("reg [7:0] q_mem_data;"))
    assert(declaration >= 0 && load >= 0 && readNet >= 0, v)
    assertEquals(load, declaration + 1, v)
    assert(load < readNet, v)

  test("the load is a power-on image, not a reset value, and appears once"):
    class Top extends Clocked:
      val addr = In(UInt(8))
      val data = In(UInt(8))
      val mem = BlockRam(256, UInt(8), HexFile("image.hex"))
      val q = mem.readSync(addr, ReadFirst)
      mem.write(addr, data)

    val v = emit(new Top)
    assertEquals(v.split("""\$readmemh""", -1).length - 1, 1, v)
    // Outside every always block: the load precedes the first one and the
    // memory's own block still has no reset branch (R15.1).
    val lines = v.linesIterator.toList
    assert(lines.indexWhere(_.contains("$readmemh")) <
      lines.indexWhere(_.contains("always @(")), v)
    val body = v.linesIterator.dropWhile(!_.contains("always @(posedge clk)"))
      .takeWhile(!_.contains("endmodule")).mkString("\n")
    assert(!body.contains("rst"), body)
    assert(!body.contains("$readmemh"), body)

  test("adding an image changes exactly one line of the emitted module"):
    class Plain extends Clocked:
      override def moduleName: String = "Same"
      val addr = In(UInt(8))
      val data = In(UInt(8))
      val enable = In(Bool())
      val mem = BlockRam(256, UInt(8))
      val q = mem.readSync(addr, WriteFirst)
      mem.write(addr, data, enable)

    class Imaged extends Clocked:
      override def moduleName: String = "Same"
      val addr = In(UInt(8))
      val data = In(UInt(8))
      val enable = In(Bool())
      val mem = BlockRam(256, UInt(8), HexFile("image.hex"))
      val q = mem.readSync(addr, WriteFirst)
      mem.write(addr, data, enable)

    val plain = emit(new Plain).linesIterator.toList
    val imaged = emit(new Imaged).linesIterator.toList
    assertEquals(imaged.filterNot(_.contains("$readmemh")), plain)
    assertEquals(imaged.length, plain.length + 1)

  test("each memory loads its own image, in declaration order"):
    class Top extends Clocked:
      val addr = In(UInt(8))
      val data = In(UInt(8))
      val lane0 = BlockRam(256, UInt(8), HexFile("ram_b0.hex"))
      val lane1 = BlockRam(256, UInt(8), HexFile("ram_b1.hex"))
      val q0 = lane0.readSync(addr, ReadFirst)
      val q1 = lane1.readSync(addr, ReadFirst)
      lane0.write(addr, data)
      lane1.write(addr, data)

    val loads = emit(new Top).linesIterator.filter(_.contains("$readmemh")).toList
    assertEquals(loads, List(
      """  initial $readmemh("ram_b0.hex", lane0);""",
      """  initial $readmemh("ram_b1.hex", lane1);"""))

  test("a one-bit element declares no range and still loads"):
    class Top extends Clocked:
      val addr = In(UInt(4))
      val mem = Registers(16, Bool(), HexFile("flags.hex"))
      val q = mem.readAsync(addr, ReadFirst)

    val v = emit(new Top)
    assert(v.contains("reg mem [0:15];"), v)
    assert(v.contains("""  initial $readmemh("flags.hex", mem);"""), v)

  test("a Bundle element loads the packed array under the memory's own name"):
    class Entry extends Bundle:
      val addr = UInt(8)
      val valid = Bool()

    class Top extends Clocked:
      val index = In(UInt(4))
      val mem = Registers(16, new Entry, HexFile("entries.hex"))
      val q = mem.readAsync(index, ReadFirst)

    val v = emit(new Top)
    assert(v.contains("reg [8:0] mem [0:15];"), v)
    assert(v.contains("""  initial $readmemh("entries.hex", mem);"""), v)

  test("the path is escaped into a Verilog string literal"):
    class Top extends Clocked:
      val addr = In(UInt(4))
      val mem = Registers(16, UInt(8), HexFile("""a\b"c.hex"""))
      val q = mem.readAsync(addr, ReadFirst)

    assert(emit(new Top).contains("""initial $readmemh("a\\b\"c.hex", mem);"""), emit(new Top))

  test("emission is deterministic"):
    class Top extends Clocked:
      val addr = In(UInt(8))
      val mem = BlockRam(256, UInt(8), HexFile("image.hex"))
      val q = mem.readSync(addr, ReadFirst)

    assertEquals(emit(new Top), emit(new Top))

  // -- diagnostics ---------------------------------------------------------

  test("a blank path raises MEM_INIT_PATH_EMPTY"):
    for blank <- List("", "   ") do
      val error = intercept[MemoryElaborationError]:
        class Bad extends Clocked:
          val addr = In(UInt(4))
          val mem = Registers(16, UInt(8), HexFile(blank))
          val q = mem.readAsync(addr, ReadFirst)
        new Bad
      assertEquals(error.code, "MEM_INIT_PATH_EMPTY")
      assert(error.getMessage.contains("image file"), error.getMessage)

  test("a control character in the path raises MEM_INIT_PATH_UNPRINTABLE"):
    val error = intercept[MemoryElaborationError]:
      class Bad extends Clocked:
        val addr = In(UInt(4))
        val mem = Registers(16, UInt(8), HexFile("image.hex\n"))
        val q = mem.readAsync(addr, ReadFirst)
      new Bad
    assertEquals(error.code, "MEM_INIT_PATH_UNPRINTABLE")
    assert(!error.getMessage.contains('\n'), error.getMessage)

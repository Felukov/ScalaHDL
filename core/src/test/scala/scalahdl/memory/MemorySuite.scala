package scalahdl.memory

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.{Vendor, VerilogEmitter}
import scalahdl.core.{MemoryKind, ReadDuringWrite, ResetPolarity, Type}
import scalahdl.clock.ResetKind

class MemorySuite extends munit.FunSuite:

  abstract class Clocked extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ResetPolarity.ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ResetPolarity.ActiveHigh)

  private def emit(m: Module): String =
    val design = m.elaborateDesign()
    new VerilogEmitter("0.1.1", Some(Vendor.Xilinx)).emitModule(design.top, design.top.name)

  test("all three explicit kinds elaborate as first-class memory IR nodes"):
    class Top extends Clocked:
      val addr = In(UInt(10))
      val regs = Registers(1024, UInt(8))
      val lut = LutRam(64, UInt(8))
      val block = BlockRam(1024, UInt(8))
      val a = regs.readAsync(addr, ReadFirst)
      val b = lut.readAsync(addr, Undefined)
      val c = block.readSync(addr, WriteFirst)

    val memories = (new Top).elaborate().memories
    assertEquals(memories.map(_.kind), List(MemoryKind.Registers, MemoryKind.LutRam, MemoryKind.BlockRam))
    assertEquals(memories.map(_.reads.head.synchronous), List(false, false, true))

  test("BlockRam readAsync errors and names readSync plus asynchronous alternatives"):
    val error = intercept[MemoryElaborationError]:
      class Bad extends Clocked:
        val addr = In(UInt(10))
        val mem = BlockRam(1024, UInt(8))
        val q = mem.readAsync(addr, ReadFirst)
      new Bad
    assertEquals(error.code, "MEM_ASYNC_READ_ON_BRAM")
    assert(error.getMessage.contains("readSync"), error.getMessage)
    assert(error.getMessage.contains("LutRam/Registers"), error.getMessage)

  test("omitting RDW policy raises MEM_RDW_POLICY_REQUIRED"):
    val error = intercept[MemoryElaborationError]:
      class Bad extends Clocked:
        val addr = In(UInt(6))
        val mem = LutRam(64, UInt(8))
        val q = mem.readAsync(addr)
      new Bad
    assertEquals(error.code, "MEM_RDW_POLICY_REQUIRED")
    assert(error.getMessage.contains("ReadFirst, WriteFirst, WriteFirstPort or Undefined"), error.getMessage)

  // R15.1 — the read register stays with the array read it registers, and the
  // write takes a block of its own. Sharing one block was what this suite
  // asserted until a Cyclone V answered a two-port memory with 8,208
  // flip-flops: a block holding two accesses states a priority no physical
  // port has, so the match fails and the array falls to registers.
  test("BlockRam sync read keeps its register, and the write is a block of its own"):
    class Top extends Clocked:
      val addr = In(UInt(10))
      val data = In(UInt(8))
      val mem = BlockRam(1024, UInt(8))
      val q = mem.readSync(addr, ReadFirst)
      mem.write(addr, data)

    val v = emit(new Top)
    assert(v.contains("(* ram_style = \"block\" *) reg [7:0] mem [0:1023];"), v)
    assert(v.contains("q_mem_data <= mem[addr];"), v)
    assert(v.contains("mem[addr] <= data;"), v)
    assertEquals(v.split("always @\\(posedge clk\\)", -1).length - 1, 2, v)
    assert(!v.matches("(?s).*(RAMB|DPROM|altsyncram).*"), v)

  test("all RDW policies have distinct matching synchronous patterns"):
    class Top extends Clocked:
      val raddr = In(UInt(6))
      val waddr = In(UInt(6))
      val data = In(UInt(8))
      val mem = LutRam(64, UInt(8))
      val oldData = mem.readSync(raddr, ReadFirst)
      val newData = mem.readSync(raddr, WriteFirst)
      val dontCare = mem.readSync(raddr, Undefined)
      mem.write(waddr, data)

    val v = emit(new Top)
    assert(v.contains("oldData_mem_data <= mem[raddr];"), v)
    assert(v.contains("newData_mem_data <= ((waddr == raddr) ? data : mem[raddr]);"), v)
    assert(v.contains("if ((waddr != raddr)) begin\n      dontCare_mem_data <= mem[raddr];\n    end"), v)

  test("async WriteFirst emits explicit collision bypass"):
    class Top extends Clocked:
      val raddr = In(UInt(6))
      val waddr = In(UInt(6))
      val data = In(UInt(8))
      val mem = LutRam(64, UInt(8))
      val q = mem.readAsync(raddr, WriteFirst)
      mem.write(waddr, data)

    val v = emit(new Top)
    assert(v.contains("assign q_mem_data = ((waddr == raddr) ? data : mem[raddr]);"), v)
    assert(v.contains("(* ram_style = \"distributed\" *)"), v)

  test("enabled write remains an explicit inference guard"):
    class Top extends Clocked:
      val addr = In(UInt(6))
      val data = In(UInt(8))
      val enable = In(Bool())
      val mem = LutRam(64, UInt(8))
      mem.write(addr, data, enable)

    val top = (new Top).elaborate()
    assertEquals(top.memories.head.writes.head.enable.nonEmpty, true)
    val v = emit(new Top)
    assert(v.contains("if (enable) begin\n      mem[addr] <= data;\n    end"), v)

  test("two BlockRam ports retain separate clock domains and policies"):
    class Top extends Module:
      val clkA = In(Clock())
      val clkB = In(Clock())
      given defaultDomain: ClockDomain = ClockDomain(clkA, ResetKind.None)
      val domainB = ClockDomain(clkB, ResetKind.None)
      val addrA = In(UInt(8))
      val addrB = In(UInt(8))
      val data = In(UInt(16))
      val mem = BlockRam(256, UInt(16))
      val a = mem.readSync(addrA, ReadFirst)
      withClock(domainB):
        val b = mem.readSync(addrB, WriteFirst)
        mem.write(addrB, data)

    val top = (new Top).elaborate()
    assertEquals(top.memories.head.reads.map(_.domain.clkName), List("clkA", "clkB"))
    assertEquals(top.memories.head.reads.map(_.policy), List(ReadDuringWrite.ReadFirst, ReadDuringWrite.WriteFirst))
    val v = new VerilogEmitter("0.1.1", Some(Vendor.Xilinx)).emitModule(top, "Top")
    assert(v.contains("always @(posedge clkA)"), v)
    assert(v.contains("always @(posedge clkB)"), v)

  /** R15.3 — the true dual-port shape: one array, two ports, each an address
    * with a read and a write on it. `WriteFirstPort` bypasses the write on its
    * *own* port and is blind to the other's, which is what a block RAM does
    * and what a vendored `scsi_dpram` written as two `always` blocks means. */
  test("WriteFirstPort bypasses its own port's write and no other"):
    class Top extends Module:
      val clk = In(Clock())
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      val addrA = In(UInt(10))
      val addrB = In(UInt(10))
      val dataA = In(UInt(8))
      val dataB = In(UInt(8))
      val weA = In(Bool())
      val weB = In(Bool())
      val mem = BlockRam(1024, UInt(8))
      val qa = mem.readSync(addrA, WriteFirstPort)
      val qb = mem.readSync(addrB, WriteFirstPort)
      when(weA) { mem.write(addrA, dataA) }
      when(weB) { mem.write(addrB, dataB) }

    val top = (new Top).elaborate()
    val v = new VerilogEmitter("0.1.1", Some(Vendor.Xilinx)).emitModule(top, "Top")
    // Each port's read is its own write enable and the array, and neither
    // carries an address comparison: one port is one address, so the
    // comparison a `WriteFirst` bypass needs is a tautology here.
    assert(v.contains("qa_mem_data <= ((weA) ? dataA : mem[addrA]);"), v)
    assert(v.contains("qb_mem_data <= ((weB) ? dataB : mem[addrB]);"), v)
    assert(!v.contains("addrB == addrA"), v)
    assert(!v.contains("addrA == addrB"), v)

  /** ...and `WriteFirst` is unchanged: it reaches every write on the clock,
    * each under the address comparison that finds the collision. */
  test("WriteFirst still bypasses every write on the clock"):
    class Top extends Module:
      val clk = In(Clock())
      given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
      val raddr = In(UInt(10))
      val waddr = In(UInt(10))
      val data = In(UInt(8))
      val mem = BlockRam(1024, UInt(8))
      val q = mem.readSync(raddr, WriteFirst)
      mem.write(waddr, data)

    val v = new VerilogEmitter("0.1.1", Some(Vendor.Xilinx)).emitModule((new Top).elaborate(), "Top")
    assert(v.contains("q_mem_data <= ((waddr == raddr) ? data : mem[raddr]);"), v)

  test("bundle elements are packed in memory and flattened at the read result"):
    case class Entry() extends Bundle:
      val addr = UInt(8)
      val valid = Bool()

    class Top extends Clocked:
      val index = In(UInt(4))
      val mem = Registers(16, Entry())
      val q = mem.readAsync(index, Undefined)

    val v = emit(new Top)
    assert(v.contains("reg [8:0] mem [0:15];"), v)
    assert(v.contains("assign q_addr = q_mem_data[8:1];"), v)
    assert(v.contains("assign q_valid = q_mem_data[0];"), v)

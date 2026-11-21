package scalahdl.memory

import scalahdl.clock.ResetKind
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.{Emission, Vendor}

/** R15.6 — a memory read takes the condition it is read under.
  *
  * The read side of R15.5, found the same way and for the same reason. A read
  * port is an address and a result register, so the `when`/`switch` nest around
  * `mem.readSync(...)` had nowhere to go and was **dropped**: the read
  * compiled, elaborated, validated, and updated its register on **every
  * clock**. What R15.5's own rationale says about a write applies here without
  * a word changed — an assignment carries its nest in the driver block that
  * holds it, and a memory port has no statement position to carry one in.
  *
  * Found porting a floppy track buffer, whose 12 KB of track is
  * read by two ports with mutually exclusive enables — the drive's byte while
  * the track is valid, the SD card's byte while it is not — written upstream as
  * one `if (ready) … else …` over one array. Without an enable the only ways to
  * spell it are a second register and a combinational output, or 98,304 bits of
  * LUT.
  *
  * So the obligations mirror R15.5's: the nest reaches the enable, outermost
  * term first; an explicit `enable` argument composes with it rather than
  * replacing it; a read at the top level is unconditional exactly as before;
  * `default` is a named error rather than a guess. And one that is this rule's
  * own — an **asynchronous** read is refused an enable altogether, because a
  * continuous array read has no register to hold a value over and a condition
  * around it cannot change what the wire carries.
  */
class MemoryReadScopeSuite extends munit.FunSuite:

  private def emit(m: => Module): String =
    new Emission("0.0.0-TEST", Some(Vendor.Xilinx)).emit(m.elaborateDesign()).files.head._2

  private def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  /** The shape this was found in: one array, two synchronous read ports whose
    * enables are a condition and its negation. */
  private class TrackBuffer extends Module:
    val clk   = In(Clock())
    val ready = In(Bool())
    val rd    = In(UInt(5))
    val wr    = In(UInt(5))
    val toDrive = Out(UInt(8))
    val toHost  = Out(UInt(8))

    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)

    val buffer = BlockRam(24, UInt(8))
    val drive  = buffer.readSync(rd, ReadFirst, ready)
    val host   = buffer.readSync(wr, ReadFirst, !ready)
    toDrive := drive
    toHost  := host

  test("two read ports with opposite enables are one array and two guarded registers"):
    val verilog = emit(new TrackBuffer)
    assert(verilog.contains("""(* ram_style = "block" *) reg [7:0] buffer [0:23];"""), verilog)
    assert(verilog.contains("if ((ready)) begin\n      drive_mem_data <= buffer[rd];\n    end"), verilog)
    assert(verilog.contains("if (((!ready))) begin\n      host_mem_data <= buffer[wr];\n    end"), verilog)
    // One `always` block **per read**, which is what R15.1 requires of an
    // inference pattern: a block is one port, and two reads at two addresses
    // are two ports. Both are on the one clock they share.
    assertEquals(verilog.linesIterator.count(_.contains("always @(posedge clk)")), 2)

  test("a read with no enable is unconditional, exactly as before"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val addr = In(UInt(4))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = BlockRam(16, UInt(8))
        val q = mem.readSync(addr, ReadFirst)
        out := q
    }
    assert(verilog.contains("q_mem_data <= mem[addr];"), verilog)
    assert(!verilog.contains("if ("), verilog)

  test("a read inside a `when` takes the condition, where it used to be dropped"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val en   = In(Bool())
        val tick = In(Bool())
        val addr = In(UInt(4))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = BlockRam(16, UInt(8))
        var taken: Option[DslSignal] = None
        when(en) { when(tick) { val q = mem.readSync(addr, ReadFirst); taken = Some(q) } }
        out := taken.get
    }
    assert(verilog.contains("if (((en & tick))) begin\n      q_mem_data <= mem[addr];\n    end"), verilog)

  test("an explicit enable composes with the scope rather than replacing it"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val a, e = In(Bool())
        val addr = In(UInt(4))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = BlockRam(16, UInt(8))
        var taken: Option[DslSignal] = None
        when(a) { val q = mem.readSync(addr, ReadFirst, e); taken = Some(q) }
        out := taken.get
    }
    assert(verilog.contains("if (((a & e))) begin\n      q_mem_data <= mem[addr];\n    end"), verilog)

  test("an `is` arm's read takes the selector's comparison"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val sel  = In(UInt(2))
        val addr = In(UInt(4))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = BlockRam(16, UInt(8))
        var taken: Option[DslSignal] = None
        switch(sel) {
          is(1) { val q = mem.readSync(addr, ReadFirst); taken = Some(q) }
          default { }
        }
        out := taken.get
    }
    assert(verilog.contains("if (((sel == 2'd1))) begin\n      q_mem_data <= mem[addr];\n    end"), verilog)

  // ---- the policies keep their own shapes, with the enable in front

  test("WriteFirst keeps its bypass and gains the enable"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val e, we = In(Bool())
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = BlockRam(16, UInt(8))
        val q = mem.readSync(addr, WriteFirst, e)
        mem.write(addr, data, we)
        out := q
    }
    assert(verilog.contains(
      "if ((e)) begin\n      q_mem_data <= ((we && (addr == addr)) ? data : mem[addr]);\n    end"), verilog)

  test("Undefined puts the enable in front of its own collision guard"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val e, we = In(Bool())
        val raddr, waddr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = BlockRam(16, UInt(8))
        val q = mem.readSync(raddr, Undefined, e)
        mem.write(waddr, data, we)
        out := q
    }
    assert(verilog.contains(
      "if ((e) && (!we || (waddr != raddr))) begin\n      q_mem_data <= mem[raddr];\n    end"), verilog)

  // ---- the two scopes a read cannot take

  test("a read in a `default` arm is refused, and says why"):
    val ds = expectFailure {
      new Module:
        val clk  = In(Clock())
        val sel  = In(UInt(2))
        val addr = In(UInt(4))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = BlockRam(16, UInt(8))
        var taken: Option[DslSignal] = None
        switch(sel) {
          is(0) { }
          default { val q = mem.readSync(addr, ReadFirst); taken = Some(q) }
        }
        out := taken.get
    }
    assertEquals(ds.map(_.code), List(Code.MEM_READ_IN_DEFAULT_ARM))
    assert(ds.head.summary.contains("memory read"), ds.head.summary)
    assert(ds.head.fix.exists(_.contains("enable")), ds.head.fix.toString)

  test("an enable on an asynchronous read is refused and names the alternative"):
    val error = intercept[MemoryElaborationError] {
      (new Module:
        val clk  = In(Clock())
        val e    = In(Bool())
        val addr = In(UInt(4))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = LutRam(16, UInt(8))
        val q = mem.readAsync(addr, ReadFirst, e)
        out := q
      ).elaborateDesign()
    }
    assertEquals(error.code, "MEM_READ_ENABLE_ON_ASYNC")
    assert(error.getMessage.contains("readSync"), error.getMessage)

  test("a `when` around an asynchronous read is the same refusal"):
    val error = intercept[MemoryElaborationError] {
      (new Module:
        val clk  = In(Clock())
        val a    = In(Bool())
        val addr = In(UInt(4))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val mem = LutRam(16, UInt(8))
        var taken: Option[DslSignal] = None
        when(a) { val q = mem.readAsync(addr, ReadFirst); taken = Some(q) }
        out := taken.get
      ).elaborateDesign()
    }
    assertEquals(error.code, "MEM_READ_ENABLE_ON_ASYNC")

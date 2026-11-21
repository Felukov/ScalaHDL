package scalahdl.memory

import scalahdl.clock.ResetKind
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** R15.5 — a memory write takes the condition it is written under.
  *
  * The requirement exists because the alternative was a silent wrong emission.
  * An assignment is recorded into a driver block that carries the
  * `When`/`Switch` nest itself, so R12's structure *is* its condition. A write
  * port is not a statement in a driver block — it is an address, data and an
  * enable — so the nest around `mem.write(...)` had nowhere to go and was
  * dropped: the write compiled, elaborated, validated, and emitted a write on
  * **every clock**. Found porting a clock chip's parameter RAM, whose twenty
  * bytes are written under three levels of condition and would have been
  * overwritten sixteen million times a second.
  *
  * So the obligations are: the nest reaches the enable, outermost term first;
  * an explicit `enable` argument still works and composes with it; a write at
  * the top level is unconditional exactly as before; and the one scope whose
  * condition is not knowable while it is open — `default`, which means every
  * value no `is` names, and R22 lets an `is` be added after it — is a named
  * error rather than a guess.
  */
class MemoryWriteScopeSuite extends munit.FunSuite:

  private def emit(m: => Module): String =
    new Emission("0.0.0-TEST").emit(m.elaborateDesign()).files.head._2

  private def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  /** The shape this was found in: a small register array written deep inside a
    * protocol's nest. */
  private class Pram extends Module:
    val clk  = In(Clock())
    val en   = In(Bool())
    val tick = In(Bool())
    val addr = In(UInt(5))
    val data = In(UInt(8))
    val out  = Out(UInt(8))

    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)

    val ram = Registers(20, UInt(8))
    val rd = ram.readAsync(addr, ReadFirst)
    out := rd
    when(en) {
      when(tick) { ram.write(addr, data) }
    }

  test("a write inside a `when` is a write under an `if`"):
    val verilog = emit(new Pram)
    assert(verilog.contains("if ((en & tick)) begin\n      ram[addr] <= data;\n    end"), verilog)

  test("a write at the top level is unconditional, exactly as before"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val ram = Registers(16, UInt(8))
        val rd = ram.readAsync(addr, ReadFirst)
        out := rd
        ram.write(addr, data)
    }
    assert(verilog.contains("ram[addr] <= data;"), verilog)
    assert(!verilog.contains("if ("), verilog)

  test("`otherwise` negates, and an else-if carries the earlier negation"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val a, b = In(Bool())
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val ram = Registers(16, UInt(8))
        val rd = ram.readAsync(addr, ReadFirst)
        out := rd
        when(a) { }.elsewhen(b) { ram.write(addr, data) }
    }
    assert(verilog.contains("if (((!a) & b)) begin\n      ram[addr] <= data;\n    end"), verilog)

  test("an explicit enable composes with the scope rather than replacing it"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val a, e = In(Bool())
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val ram = Registers(16, UInt(8))
        val rd = ram.readAsync(addr, ReadFirst)
        out := rd
        when(a) { ram.write(addr, data, e) }
    }
    assert(verilog.contains("if ((a & e)) begin\n      ram[addr] <= data;\n    end"), verilog)

  // ---- R22 and R26 arms

  test("a write in an `is` arm takes the selector's comparison"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val sel  = In(UInt(2))
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val ram = Registers(16, UInt(8))
        val rd = ram.readAsync(addr, ReadFirst)
        out := rd
        switch(sel) {
          is(1) { ram.write(addr, data) }
          default { }
        }
    }
    assert(verilog.contains("if ((sel == 2'd1)) begin\n      ram[addr] <= data;\n    end"), verilog)

  test("an aliased arm takes the union of its labels"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val sel  = In(UInt(2))
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val ram = Registers(16, UInt(8))
        val rd = ram.readAsync(addr, ReadFirst)
        out := rd
        switch(sel) {
          is(1, 2) { ram.write(addr, data) }
          default { }
        }
    }
    assert(verilog.contains("if (((sel == 2'd1) | (sel == 2'd2))) begin\n      ram[addr] <= data;\n    end"),
           verilog)

  test("a write in an `on` arm takes the condition itself (R26)"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val a, b = In(Bool())
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val ram = Registers(16, UInt(8))
        val rd = ram.readAsync(addr, ReadFirst)
        out := rd
        priorityCase {
          on(a) { ram.write(addr, data) }
          on(b) { }
        }
    }
    assert(verilog.contains("if (a) begin\n      ram[addr] <= data;\n    end"), verilog)

  test("the nest reaches the enable outermost term first"):
    val verilog = emit {
      new Module:
        val clk  = In(Clock())
        val sel  = In(UInt(2))
        val g    = In(Bool())
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val ram = Registers(16, UInt(8))
        val rd = ram.readAsync(addr, ReadFirst)
        out := rd
        when(g) {
          switch(sel) {
            is(3) { ram.write(addr, data) }
            default { }
          }
        }
    }
    assert(verilog.contains("if ((g & (sel == 2'd3))) begin\n      ram[addr] <= data;\n    end"), verilog)

  // ---- the one scope that cannot answer

  test("a write in a `default` arm is refused, and says why"):
    val ds = expectFailure {
      new Module:
        val clk  = In(Clock())
        val sel  = In(UInt(2))
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val out  = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val ram = Registers(16, UInt(8))
        val rd = ram.readAsync(addr, ReadFirst)
        out := rd
        switch(sel) {
          is(0) { }
          default { ram.write(addr, data) }
        }
    }
    assertEquals(ds.map(_.code), List(Code.MEM_WRITE_IN_DEFAULT_ARM))
    assert(ds.head.summary.contains("default"), ds.head.summary)
    assert(ds.head.fix.exists(_.contains("enable")), ds.head.fix.toString)

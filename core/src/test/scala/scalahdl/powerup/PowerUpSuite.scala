package scalahdl.powerup

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** R13.4 — `init` is the reset value and `powerUp` the value a bitstream
  * loads, whatever the domain.
  *
  * A domain with no reset used to write `init` on the declaration, so one
  * argument meant either value depending on where the register lived — and
  * giving that domain a reset withdrew the register's value at configuration
  * with no line of the source changed. Every peripheral in a borrowed
  * machine is written with a value and no reset (`reg [15:0] shiftRegister =
  * 16'hffff; // prevent white screen during reset`), which is how the trap was
  * reached.
  *
  * So the obligations are: `powerUp` reaches the declaration in any domain;
  * `init` where nothing resets is refused and pointed at `powerUp`; moving a
  * register between domains either keeps what it claims or is refused; and the
  * two things a declaration cannot be initialised with are errors rather than
  * surprises.
  */
class PowerUpSuite extends munit.FunSuite:

  private def emit(m: => Module): String =
    new Emission("0.0.0-TEST").emit(m.elaborateDesign()).files.head._2

  private def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  /** A shift register with a power-up value and nothing to reset it — the
    * shape of every video, timing and glue module in a machine whose only
    * reset is the one its ROM performs. */
  private class Shifter extends Module:
    val clk = In(Clock())
    val en = In(Bool())
    val load = In(Bool())
    val data = In(UInt(16))
    val out = Out(Bool())

    given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)

    val shifter = Reg(UInt(16), powerUp = 0xffff.U(16))
    when(en) {
      when(load) { shifter := data }
        .otherwise { shifter := Cat(shifter(14, 0), True) }
    }
    out := ~shifter(15)

  test("a domain with no reset writes the power-up value on the declaration"):
    val verilog = emit(new Shifter)
    assert(verilog.contains("reg [15:0] shifter = 16'd65535;"), verilog)
    // and the always block is R13's reset-free one, unchanged
    assert(verilog.contains("always @(posedge clk) begin"), verilog)
    assert(!verilog.contains("if (rst)"), verilog)

  test("a one-bit power-up value keeps its own spelling"):
    class Flag extends Module:
      val clk = In(Clock())
      val set = In(Bool())
      val q = Out(Bool())
      given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
      val armed = Reg(Bool(), powerUp = True)
      when(set) { armed := False }
      q := armed
    assert(emit(new Flag).contains("reg armed = 1'b1;"), emit(new Flag))

  test("a bare integer power-up value takes the register's width"):
    class Counter extends Module:
      val clk = In(Clock())
      val q = Out(UInt(8))
      given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
      val count = Reg(UInt(8), powerUp = 5)
      count := count + 1.U(8)
      q := count
    val verilog = emit(new Counter)
    assert(verilog.contains("reg [7:0] count = 8'd5;"), verilog)

  test("a register with no value in a domain with no reset declares no value"):
    class Plain extends Module:
      val clk = In(Clock())
      val d = In(UInt(8))
      val q = Out(UInt(8))
      given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
      val held = Reg(UInt(8))
      held := d
      q := held
    assert(emit(new Plain).contains("reg [7:0] held;"), emit(new Plain))

  test("an init where nothing resets is refused, and the fix names powerUp"):
    class Unreset extends Module:
      val clk = In(Clock())
      val q = Out(UInt(8))
      given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
      val count = Reg(UInt(8), init = 5.U(8))
      count := count + 1.U(8)
      q := count
    val diagnostics = expectFailure(new Unreset)
    assertEquals(diagnostics.map(_.code), List(Code.INIT_WITHOUT_RESET))
    assert(diagnostics.head.summary.contains("count"), diagnostics.head.summary)
    assert(diagnostics.head.fix.exists(_.contains("powerUp")), diagnostics.head.fix.toString)

  test("an aggregate init where nothing resets is refused by the same rule"):
    class Packet extends Bundle:
      val addr = UInt(8)
      val valid = Bool()
    class Aggregate extends Module:
      val clk = In(Clock())
      val take = In(Bool())
      val q = Out(UInt(8))
      given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
      val packet = Reg(new Packet, init = 0.U(9))
      when(take) { packet.valid := True }
      q := packet.addr
    assertEquals(expectFailure(new Aggregate).map(_.code), List(Code.INIT_WITHOUT_RESET))

  test("a domain with a reset is unchanged: the init is the reset value and is written once"):
    class Counter extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      val q = Out(UInt(8))
      given clocking: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val count = Reg(UInt(8), init = 7.U(8))
      count := count + 1.U(8)
      q := count
    val verilog = emit(new Counter)
    assert(verilog.contains("reg [7:0] count;"), verilog)
    assert(verilog.contains("if (rst) begin\n      count <= 8'd7;\n    end"), verilog)

  test("an asynchronous reset is unchanged too — R13 already carries the value"):
    class Async extends Module:
      val clk = In(Clock())
      val rst_n = In(Reset(ActiveLow))
      val q = Out(Bool())
      given clocking: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
      val armed = Reg(Bool(), init = True)
      armed := !armed
      q := armed
    val verilog = emit(new Async)
    assert(verilog.contains("reg armed;"), verilog)
    assert(verilog.contains("always @(posedge clk or negedge rst_n) begin"), verilog)

  test("moving a register into a domain that resets keeps its power-up value"):
    // The trap this rule closes: the same declaration, once with no reset and
    // once with one. Before, `init` moved from the declaration to the reset
    // branch and the value at configuration was gone without a word.
    abstract class Shifted extends Module:
      val clk = In(Clock())
      val q = Out(Bool())
    class Unreset extends Shifted:
      given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
      val armed = Reg(Bool(), powerUp = True)
      armed := !armed
      q := armed
    class Resetting extends Shifted:
      val rst = In(scalahdl.dsl.Reset(ActiveHigh))
      given clocking: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val armed = Reg(Bool(), powerUp = True)
      armed := !armed
      q := armed
    assert(emit(new Unreset).contains("reg armed = 1'b1;"), emit(new Unreset))
    val moved = emit(new Resetting)
    assert(moved.contains("reg armed = 1'b1;"), moved)
    assert(!moved.contains("if (rst)"), moved)

  test("a power-up value that is not a constant is refused, not dropped"):
    class Copied extends Module:
      val clk = In(Clock())
      val seed = In(UInt(8))
      val q = Out(UInt(8))
      given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
      val held = Reg(UInt(8), powerUp = seed)
      held := held + 1.U(8)
      q := held
    val diagnostics = expectFailure(new Copied)
    assertEquals(diagnostics.map(_.code), List(Code.POWER_UP_NOT_CONSTANT))
    assert(diagnostics.head.summary.contains("held"), diagnostics.head.summary)

  test("an aggregate power-up value is refused: a leaf per net has no one declaration"):
    class Packet extends Bundle:
      val addr = UInt(8)
      val valid = Bool()
    class Aggregate extends Module:
      val clk = In(Clock())
      val take = In(Bool())
      val q = Out(UInt(8))
      given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
      val packet = Reg(new Packet, powerUp = 0.U(9))
      when(take) { packet.valid := True }
      q := packet.addr
    val diagnostics = expectFailure(new Aggregate)
    assertEquals(diagnostics.map(_.code), List(Code.POWER_UP_AGGREGATE))
    assert(diagnostics.head.fix.exists(_.contains("scalar registers")),
           diagnostics.head.fix.toString)

  // ---- R13.8 — a power-up value and a reset value are two claims ------------

  test("a register may carry both a power-up value and a reset value"):
    class Top extends Module:
      val clk  = In(Clock())
      val rstn = In(Reset(ActiveLow))
      val hold = In(UInt(20))
      val en   = In(Bool())
      val busy = Out(Bool())
      given d: ClockDomain = ClockDomain(clk, rstn, ResetKind.Async, ActiveLow)
      // The shape a reset controller has: a bitstream loads the long
      // count, a reset loads whichever count this compilation is for, and the
      // two are different events with different values.
      val delay = Reg(UInt(20), init = hold, powerUp = 0xFFFFF.U(20))
      when(en & (delay =/= 0.U(20))) { delay := delay - 1 }
      busy := delay =/= 0.U(20)

    val verilog = new Emission("t").emit((new Top).elaborateDesign(), "Top").files.head._2
    // the declaration carries the power-up value...
    assert(verilog.contains("reg [19:0] delay = 20'd1048575;"), verilog)
    // ...and the reset branch carries the reset value, which is not a constant
    assert(verilog.contains("if (!rstn) begin\n      delay <= hold;\n    end"), verilog)

  test("a power-up value must still be a constant"):
    class Top extends Module:
      val clk  = In(Clock())
      val rstn = In(Reset(ActiveLow))
      val hold = In(UInt(20))
      given d: ClockDomain = ClockDomain(clk, rstn, ResetKind.Async, ActiveLow)
      val delay = Reg(UInt(20), init = 0.U(20), powerUp = hold)
      delay := delay
    val ex = intercept[ValidationFailed]((new Top).elaborateDesign())
    assert(ex.diagnostics.exists(_.code == Code.POWER_UP_NOT_CONSTANT),
           ex.diagnostics.map(_.code).mkString(","))

  test("in a domain with no reset an init beside a power-up value is still refused"):
    class Top extends Module:
      val clk = In(Clock())
      given d: ClockDomain = ClockDomain(clk, ResetKind.None)
      val r = Reg(UInt(8), init = 1.U(8), powerUp = 2.U(8))
      r := r
    val ex = intercept[ValidationFailed]((new Top).elaborateDesign())
    assertEquals(ex.diagnostics.map(_.code), List(Code.INIT_WITHOUT_RESET))


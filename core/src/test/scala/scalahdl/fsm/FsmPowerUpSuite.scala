package scalahdl.fsm

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.emit.Emission

/** R25 with R13.4/R13.8 — a machine's two starting states.
  *
  * `init` is the state a reset loads and `powerUp` the state a bitstream loads,
  * on exactly `Reg`'s terms. Before, a machine had only `init`, so one in a
  * domain that resets had no way to say where it stands at configuration, and
  * one in a domain that does not had `init` meaning the other thing. */
class FsmPowerUpSuite extends munit.FunSuite:
  import FsmFixtures.{Light, emitted, fsmOf}

  abstract class Resetting extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  abstract class Unreset extends Module:
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)

  /** Comes up `On`, and a reset puts it `Off`: the two claims differ. */
  class Both extends Resetting:
    val press = In(Bool())
    val state = Fsm(Light, init = Light.Off, powerUp = Light.On) {
      in(Light.Off) { when(press) { goto(Light.On) } }
      in(Light.On) { goto(Light.Broken) }
      terminal(Light.Broken)
    }

  test("a machine in a domain that resets may carry a power-up state"):
    val verilog = emitted(new Both)
    assert(verilog.contains("reg [1:0] state = 2'd1;"), verilog)
    assert(verilog.contains("if (rst) begin\n      state <= 2'd0;\n    end"), verilog)

  test("a machine in a domain with no reset starts from its power-up state"):
    class Blinker extends Unreset:
      val fault = In(Bool())
      val state = Fsm(Light, powerUp = Light.On) {
        in(Light.On) { goto(Light.Off); when(fault) { goto(Light.Broken) } }
        in(Light.Off) { goto(Light.On) }
        terminal(Light.Broken)
      }
    val verilog = emitted(new Blinker)
    assert(verilog.contains("reg [1:0] state = 2'd1;"), verilog)
    // `Off` is reached from `On`, the only start there is
    assertEquals(fsmOf(new Blinker).entryCodes, List(BigInt(1)))

  test("init in a machine whose domain has no reset is refused, as on Reg"):
    class Configured extends Unreset:
      val fault = In(Bool())
      val state = Fsm(Light, init = Light.Off) {
        in(Light.Off) { goto(Light.On) }
        in(Light.On) { goto(Light.Off); when(fault) { goto(Light.Broken) } }
        terminal(Light.Broken)
      }
    val ex = intercept[ValidationFailed]((new Configured).elaborateDesign())
    assertEquals(ex.diagnostics.map(_.code), List(Code.INIT_WITHOUT_RESET))

  test("a machine with neither init nor powerUp has nowhere to start"):
    class Nowhere extends Resetting:
      val state = Fsm(Light) {
        in(Light.Off) { goto(Light.On) }
        in(Light.On) { goto(Light.Off) }
      }
    val ex = intercept[ValidationFailed]((new Nowhere).elaborateDesign())
    assertEquals(ex.diagnostics.map(_.code), List(Code.FSM_NO_INITIAL_STATE))

  test("a state reached only from the power-up state is reachable"):
    // `On` has no incoming edge; the bitstream is its only way in. `Broken`
    // has none either and is still refused, so reachability is still checked.
    class Once extends Resetting:
      val state = Fsm(Light, init = Light.Off, powerUp = Light.On) {
        in(Light.On) { goto(Light.Off) }
        terminal(Light.Off)
        terminal(Light.Broken)
      }
    val ex = intercept[ValidationFailed]((new Once).elaborateDesign())
    assertEquals(ex.diagnostics.map(_.code), List(Code.FSM_STATE_UNREACHABLE))
    assert(ex.diagnostics.head.summary.contains("`Light.Broken`"), ex.diagnostics.head.summary)
    assert(ex.diagnostics.head.summary.contains("from `Light.Off` or `Light.On`"),
      ex.diagnostics.head.summary)

  test("the report names both starts when they differ, and one when they do not"):
    def report(m: Module): String =
      new Emission().emit(m.elaborateDesign()).reports
        .find(_._1.endsWith(".fsm.md")).map(_._2).getOrElse(fail("no report"))
    val both = report(new Both)
    assert(both.contains("initial Off on reset, On at power-up"), both)
    assert(both.contains("[*] --> Off\n  [*] --> On\n"), both)

    class Same extends Resetting:
      val state = Fsm(Light, init = Light.Off, powerUp = Light.Off) {
        in(Light.Off) { goto(Light.On) }
        in(Light.On) { goto(Light.Broken) }
        terminal(Light.Broken)
      }
    val same = report(new Same)
    assert(same.contains("2 bits, initial Off\n"), same)
    assertEquals(same.linesIterator.count(_.contains("[*] -->")), 1, same)

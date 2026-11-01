package scalahdl.cdc

import munit.FunSuite
import scalahdl.elab.Module
import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.core.CdcReport
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.emit.VerilogEmitter

/** R05.7 — an input declared asynchronous.
  *
  * A port is neutral, which is what makes a module's boundary free; a pin a
  * part with its own crystal drives is the one input that is not. Declaring it
  * `Asynchronous` gives it a domain foreign to every clock, so the synchroniser
  * every such pin needs is the rule rather than the convention, and the crossing
  * appears in the CDC report beside the ones between two clocks.
  */
final class AsynchronousInputSuite extends FunSuite:

  private abstract class Clocked extends Module:
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)

  test("a register loaded straight from an asynchronous input is a crossing"):
    class Top extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val sampled = Reg(Bool())
      sampled := rxd

    val failure = intercept[ValidationFailed]((new Top).elaborate())
    assertEquals(failure.diagnostics.head.code, Code.CDC_UNSAFE_CROSSING)
    assert(failure.diagnostics.head.summary.contains("rxd"), failure.diagnostics.head.summary)
    assert(failure.diagnostics.head.summary.contains("asynchronous"),
      failure.diagnostics.head.summary)

  test("two flops written by hand are refused, and the same design without the marker is not"):
    class ByHand extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val meta = Reg(Bool(), powerUp = 1)
      val sync = Reg(Bool(), powerUp = 1)
      meta := rxd
      sync := meta
    intercept[ValidationFailed]((new ByHand).elaborate())

    // The rule is opt-in: the pin that says nothing about itself is read as
    // before, which is the state every project is in until it marks its pins.
    class Unmarked extends Clocked:
      val rxd = In(Bool())
      val meta = Reg(Bool(), powerUp = 1)
      val sync = Reg(Bool(), powerUp = 1)
      meta := rxd
      sync := meta
    (new Unmarked).elaborate()

  test("SyncChain takes it, and the report names the crossing"):
    class Top extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val level = Out(Bool())
      level := SyncChain(rxd)

    val module = (new Top).elaborate()
    val report = CdcReport.render(module)
    assert(report.contains("rxd: asynchronous -> clk via SyncChain(2)"), report)
    assertEquals(module.signals.count(_.name.startsWith("sync")), 2)

  test("PulseSync and AsyncFifo are refused, because a pin has no clock to assume"):
    class Pulsed extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val event = Out(Bool())
      event := PulseSync(rxd)
    val pulse = intercept[ValidationFailed]((new Pulsed).elaborate())
    assert(pulse.diagnostics.exists(d =>
      d.summary.contains("PulseSync") && d.summary.contains("asynchronous input")),
      pulse.diagnostics.map(_.summary).mkString("\n"))

    class Queued extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val word = Out(Bool())
      word := AsyncFifo(rxd)
    val fifo = intercept[ValidationFailed]((new Queued).elaborate())
    assert(fifo.diagnostics.exists(d =>
      d.summary.contains("AsyncFifo") && d.summary.contains("asynchronous input")),
      fifo.diagnostics.map(_.summary).mkString("\n"))

  test("the unsafe hatch still admits one, with its justification in the report"):
    class Top extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val sampled = Reg(Bool())
      sampled := unsafeCrossDomain(rxd, "a level the driver holds for a thousand clocks")

    val report = CdcReport.render((new Top).elaborate())
    assert(report.contains("rxd: asynchronous -> clk via unsafeCrossDomain"), report)
    assert(report.contains("a level the driver holds for a thousand clocks"), report)

  test("a parent passes its own pin down, and drives a child's from a register"):
    class Child extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val level = Out(Bool())
      level := SyncChain(rxd)

    // The pin travels to the module that synchronises it, and needs no marker
    // on the way: an input declares what the module it belongs to may assume.
    class Parent extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val level = Out(Bool())
      val child = inst(new Child)
      child.rxd := rxd
      level := child.level
    (new Parent).elaborate()

    // And what arrives may be perfectly synchronous; the child synchronises it
    // anyway, which costs two flops and claims nothing about its source.
    class Driven extends Clocked:
      val enable = In(Bool())
      val level = Out(Bool())
      val held = Reg(Bool(), powerUp = 0)
      held := enable
      val child = inst(new Child)
      child.rxd := held
      level := child.level
    (new Driven).elaborate()

  test("a module with no clock may read it, because nothing samples it"):
    class PassThrough extends Module:
      given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
      val rxd = In(Bool(), Asynchronous)
      val level = Out(Bool())
      level := rxd
    (new PassThrough).elaborate()

  test("a declaration cannot land in the asynchronous domain"):
    // The domain stands for a pin's own transitions. Nothing in the design has
    // an edge on those, so a register in it is a clock that does not exist —
    // refused however it is named: by a `given`, by a region, or on the
    // declaration itself.
    class Given extends Module:
      given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.asynchronous
      val rxd = In(Bool(), Asynchronous)
      val held = Reg(Bool(), powerUp = 0)
      held := rxd
    val supplied = intercept[ValidationFailed]((new Given).elaborate())
    assertEquals(supplied.diagnostics.head.code, Code.ASYNCHRONOUS_DOMAIN_DECLARED)

    class Named extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val held = Reg(Bool(), powerUp = 0, domain = scalahdl.clock.ClockDomain.asynchronous)
      held := rxd
    val named = intercept[ValidationFailed]((new Named).elaborate())
    assertEquals(named.diagnostics.head.code, Code.ASYNCHRONOUS_DOMAIN_DECLARED)

    class Region extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val level = Out(Bool())
      withClock(scalahdl.clock.ClockDomain.asynchronous) {
        val held = Reg(Bool(), powerUp = 0)
        held := rxd
      }
      level := rxd
    val region = intercept[ValidationFailed]((new Region).elaborate())
    assertEquals(region.diagnostics.head.code, Code.ASYNCHRONOUS_DOMAIN_DECLARED)

  test("the marker changes no hardware"):
    class Marked extends Clocked:
      val rxd = In(Bool(), Asynchronous)
      val level = Out(Bool())
      level := SyncChain(rxd)

    class Plain extends Clocked:
      val rxd = In(Bool())
      val level = Out(Bool())
      level := SyncChain(rxd)

    // The two designs differ only in the name their class carries, so that
    // name is the one thing normalised before the texts are compared.
    def verilog(module: scalahdl.core.Module, name: String): String =
      new VerilogEmitter("0.1.1").emitModule(module, "Top")
        .replace(s"module $name(", "module Pin(")

    val marked = verilog((new Marked).elaborate(), "Marked")
    assertEquals(marked, verilog((new Plain).elaborate(), "Plain"))
    assert(marked.contains("input rxd"), marked)

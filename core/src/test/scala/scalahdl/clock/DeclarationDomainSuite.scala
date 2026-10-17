package scalahdl.clock

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.emit.VerilogEmitter

/** R4.1 — A declaration may name its domain.
  *
  * `Reg`, `Wire` and `Fsm` take an optional `domain`. Omitted, a declaration
  * resolves exactly as it did before — `activeDomain`, which prefers a
  * `withClock` scope over the `using` default. Supplied, it wins over both:
  * innermost wins, which is the precedence R13.2 already promised when it said
  * "explicit override remains available".
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M29-declaration-domain/R4.1-declaration-domain.md`. */
class DeclarationDomainSuite extends munit.FunSuite:

  /** Two domains on one module: the `using` default and a named second one.
    * They differ in *clock*, so R05 can tell them apart (`isForeignTo` is
    * clock identity) and a signal's recorded domain is observable. */
  abstract class TwoDomains extends Module:
    val clk       = In(Clock())
    val rst_n     = In(Reset(ActiveLow))
    val slowClk   = In(Clock())
    val slowRst_n = In(Reset(ActiveLow))
    given fast: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
    lazy val slow: ClockDomain = ClockDomain(slowClk, slowRst_n, ResetKind.Async, ActiveLow)

  private def domainOf(m: scalahdl.core.Module, name: String): ClockDomain =
    m.signals.find(_.name == name)
      .getOrElse(fail(s"no signal named `$name` in ${m.signals.map(_.name)}"))
      .domain

  // ---- the argument, and that it wins ----------------------------------

  test("a Reg that names a domain is declared in it, not in the using default"):
    class M extends TwoDomains:
      val bare  = Reg(UInt(8), init = 0)
      val named = Reg(UInt(8), init = 0, domain = slow)
      bare  := bare + 1
      named := named + 1
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "bare").clkName, "clk")
    assertEquals(domainOf(top, "named").clkName, "slowClk")

  test("a Wire that names a domain is declared in it"):
    class M extends TwoDomains:
      val bare  = Wire(UInt(8))
      val named = Wire(UInt(8), domain = slow)
      bare  := 1
      named := 2
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "bare").clkName, "clk")
    assertEquals(domainOf(top, "named").clkName, "slowClk")

  test("an Fsm that names a domain puts its state register in it"):
    object State extends Encoding(2):
      val Idle, Run = Value
    class M extends TwoDomains:
      val state = Fsm(State, init = State.Idle, domain = slow):
        in(State.Idle) { goto(State.Run) }
        in(State.Run)  { goto(State.Idle) }
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "state").clkName, "slowClk")

  test("the argument wins over an enclosing withClock — innermost wins"):
    class M extends TwoDomains:
      val third   = In(Clock())
      val thirdRst = In(Reset(ActiveLow))
      lazy val other: ClockDomain =
        ClockDomain(third, thirdRst, ResetKind.Async, ActiveLow)
      val scoped = Reg(UInt(8), init = 0)
      scoped := scoped + 1
      withClock(other):
        // Declared under `withClock(other)`, but says `slow` — and means it.
        val named = Reg(UInt(8), init = 0, domain = slow)
        named := named + 1
        // Its neighbour, saying nothing, still takes the scope.
        val quiet = Reg(UInt(8), init = 0)
        quiet := quiet + 1
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "scoped").clkName, "clk")
    assertEquals(domainOf(top, "named").clkName, "slowClk")
    assertEquals(domainOf(top, "quiet").clkName, "third")

  // ---- omitted, nothing changes ----------------------------------------

  test("omitted, withClock still overrides the using default (R13.2 intact)"):
    class M extends TwoDomains:
      val outer = Reg(UInt(8), init = 0)
      outer := outer + 1
      withClock(slow):
        val inner = Reg(UInt(8), init = 0)
        inner := inner + 1
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "outer").clkName, "clk")
    assertEquals(domainOf(top, "inner").clkName, "slowClk")

  test("the given-block form and the argument produce the same Verilog"):
    class ViaBlock extends TwoDomains:
      val q = Out(UInt(8))
      val count = { given ClockDomain = slow; Reg(UInt(8), init = 0) }
      count := count + 1
      q := count
    class ViaArgument extends TwoDomains:
      val q = Out(UInt(8))
      val count = Reg(UInt(8), init = 0, domain = slow)
      count := count + 1
      q := count
    val emitter = new VerilogEmitter("0.1.0-SNAPSHOT")
    // The emitted module name comes from the class (R16), so only that one
    // token may differ — the claim is that the *design* is byte for byte the
    // same however the domain was said.
    val fromBlock    = emitter.emitModule((new ViaBlock).elaborateDesign().top, "M")
      .replace("ViaBlock", "M")
    val fromArgument = emitter.emitModule((new ViaArgument).elaborateDesign().top, "M")
      .replace("ViaArgument", "M")
    assertEquals(fromArgument, fromBlock)
    assert(fromArgument.contains("always @(posedge slowClk or negedge slowRst_n)"),
      s"the register is clocked by the domain it named: $fromArgument")

  // ---- R1 name capture is unaffected ------------------------------------

  test("R1 capture survives the added parameter, supplied and omitted"):
    object State extends Encoding(2):
      val Idle, Run = Value
    class M extends TwoDomains:
      val plainReg   = Reg(UInt(8), init = 0)
      val domainReg  = Reg(UInt(8), init = 0, domain = slow)
      val plainWire  = Wire(UInt(8))
      val domainWire = Wire(UInt(8), domain = slow)
      val plainFsm = Fsm(State, init = State.Idle):
        in(State.Idle) { goto(State.Run) }
        in(State.Run)  { goto(State.Idle) }
      val domainFsm = Fsm(State, init = State.Idle, domain = slow):
        in(State.Idle) { goto(State.Run) }
        in(State.Run)  { goto(State.Idle) }
      plainReg  := plainReg + 1
      domainReg := domainReg + 1
      plainWire  := 1
      domainWire := 2
    val top = (new M).elaborateDesign().top
    val names = top.signals.map(_.name).toSet
    List("plainReg", "domainReg", "plainWire", "domainWire", "plainFsm", "domainFsm")
      .foreach(n => assert(names.contains(n), s"R1 lost `$n`: ${top.signals.map(_.name)}"))

  // ---- every shape the typeclasses accept -------------------------------

  test("a named domain reaches every Reg and Wire shape"):
    object AluOp extends Encoding(2):
      val Add, Sub = Value
    class Req(w: Int) extends Bundle:
      val addr = UInt(w)
      val data = UInt(w)
    class M extends TwoDomains:
      val encReg  = Reg(AluOp(), init = AluOp.Add, domain = slow)
      val encWire = Wire(AluOp(), domain = slow)
      val bunReg  = Reg(new Req(8), domain = slow)
      val bunWire = Wire(new Req(8), domain = slow)
      val vecReg  = Reg(Vec(2, new Req(8)), domain = slow)
      val vecWire = Wire(Vec(2, new Req(8)), domain = slow)
      encReg := encReg
      encWire := AluOp.Sub
      bunReg.addr := 0; bunReg.data := 0
      bunWire.addr := 0; bunWire.data := 0
      vecReg(0).addr := 0; vecReg(0).data := 0
      vecReg(1).addr := 0; vecReg(1).data := 0
      vecWire(0).addr := 0; vecWire(0).data := 0
      vecWire(1).addr := 0; vecWire(1).data := 0
    val top = (new M).elaborateDesign().top
    val slowNamed = top.signals.filter(_.domain.clkName == "slowClk").map(_.name)
    List("encReg", "encWire", "bunReg", "bunWire", "vecReg", "vecWire").foreach(n =>
      assert(slowNamed.exists(_.startsWith(n)),
        s"`$n` did not reach the named domain: $slowNamed"))

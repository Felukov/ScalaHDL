package scalahdl.fsm

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.Emission

/** R25 — the surface, and the claim that it is only a surface.
  *
  * The load-bearing test in this file is `emits the same Verilog as the switch
  * it stands for`: R25 promises that a machine is an R22 switch on a register
  * and nothing more, so the two spellings have to come out byte for byte the
  * same. Everything else here — name capture, the IR shape, what `stay` and
  * `terminal` emit — is a consequence of that promise, tested separately so a
  * failure says which part broke. */
class FsmSurfaceSuite extends munit.FunSuite:
  import FsmFixtures.*

  test("the machine declares the state register under the `val`'s name (R1)"):
    val top = (new Machine).elaborateDesign().top
    val state = top.signals.find(_.name == "state").getOrElse(fail("no signal named `state`"))
    assertEquals(state.kind, SignalKind.Reg)
    assertEquals(state.typeOf, Type.UInt(2))
    assertEquals(state.init, Some(Expr.uint(0, 2)), "the initial state is the register's reset value")
    assertEquals(state.encoding.map(_.name), Some("Light"), "R24 metadata rides along")

  test("the register is an ordinary signal at every other use site"):
    // `busy` reads the machine's register with `===` and a `Mux`, which is the
    // whole point of returning it rather than a handle.
    val verilog = emitted(new Machine)
    assert(verilog.contains("busy = (state != 2'd0);"), verilog)

  test("a machine elaborates to a first-class Stmt.Switch, not a new node"):
    val stmts = driverFor(new Machine, "state")
    assertEquals(stmts.length, 1)
    val sw = stmts.head match
      case s: Stmt.Switch => s
      case other => fail(s"expected a Stmt.Switch, got $other")
    assertEquals(sw.cases.length, 3, "one arm per state, `terminal` included")
    assertEquals(sw.cases.map(_.values.map(litOf)), List(List(0), List(1), List(2)))

  test("emits the same Verilog as the switch it stands for"):
    // The same machine written both ways. If R25 ever becomes more than a
    // surface construct, this is the test that says so.
    assertEquals(bodyOf(emitted(new Machine)), bodyOf(emitted(new MachineAsSwitch)))

  test("`terminal` emits an arm with no statements"):
    val sw = onlySwitch(new Machine, "state")
    assertEquals(sw.cases.last.stmts, Nil, "the terminal state assigns nothing")

  test("`stay` emits nothing and records the self-loop"):
    val fsm = fsmOf(new Holder)
    val sw = onlySwitch(new Holder, "state")
    val holding = sw.cases.find(_.values.map(litOf) == List(1)).getOrElse(fail("no arm for 1"))
    // The arm holds the two conditional exits and nothing else: `stay` did not
    // add a statement, because R11.1 already holds a register that is not
    // assigned. What it added is the recorded self-loop below.
    assertEquals(holding.stmts.length, 2, s"`stay` emits nothing: ${holding.stmts}")
    assert(holding.stmts.forall(_.isInstanceOf[Stmt.When]), holding.stmts.toString)
    assert(fsm.transitions.exists(t => t.from.contains(BigInt(1)) && t.selfLoop),
      s"the self-loop is recorded: ${fsm.transitions}")

  test("a state's body may be an ordinary method (the scope is dynamic)"):
    // `Machine` writes its states as method calls, exactly as a real machine
    // does; that it elaborates at all is the property, and the IR above is the
    // same one the inline spelling produces.
    val viaMethods = onlySwitch(new Machine, "state")
    val inline = onlySwitch(new MachineAsSwitch, "state")
    assertEquals(viaMethods.cases.map(c => c.values.map(litOf) -> c.stmts.map(stripLocs)),
                 inline.cases.map(c => c.values.map(litOf) -> c.stmts.map(stripLocs)))

  test("states may be produced by a Scala loop"):
    val fsm = fsmOf(new Generated)
    assertEquals(fsm.states.map(_.code), List(BigInt(0), BigInt(1), BigInt(2), BigInt(3)))
    assertEquals(fsm.transitions.length, 4, "one `goto` per generated state")

object FsmFixtures:

  object Light extends Encoding(2):
    val Off, On, Broken = Value

  object Four extends Encoding(2):
    val A, B, C, D = Value

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  /** The machine as R25 means it to be written: a dispatch table over named
    * steps, each step an ordinary method. */
  class Machine extends Fix:
    val press = In(Bool())
    val fault = In(Bool())
    val busy = Out(Bool())

    val state = Fsm(Light, init = Light.Off) {
      in(Light.Off) { idle() }
      in(Light.On) { lit() }
      terminal(Light.Broken)
    }

    busy := state =/= Light.Off

    def idle(): Unit =
      when(press) { goto(Light.On) }

    def lit(): Unit =
      goto(Light.Off)
      when(fault) { goto(Light.Broken) }

  /** The same machine as an R22 switch on a plain register — the reference for
    * the byte-identity test. */
  class MachineAsSwitch extends Fix:
    val press = In(Bool())
    val fault = In(Bool())
    val busy = Out(Bool())

    val state = Reg(Light(), init = Light.Off)

    switch(state) {
      is(Light.Off) {
        when(press) { state := Light.On }
      }
      is(Light.On) {
        state := Light.Off
        when(fault) { state := Light.Broken }
      }
      is(Light.Broken) {}
    }

    busy := state =/= Light.Off

  /** A machine with a state that deliberately holds: `On` leaves only when a
    * condition says so, and `stay()` is where the source says the fall-through
    * is intended rather than forgotten. */
  class Holder extends Fix:
    val go = In(Bool())
    val fault = In(Bool())
    val state = Fsm(Light, init = Light.Off) {
      in(Light.Off) { goto(Light.On) }
      in(Light.On) {
        when(go) { goto(Light.Off) }
        when(fault) { goto(Light.Broken) }
        stay()
      }
      terminal(Light.Broken)
    }

  /** Alternatives produced by an ordinary Scala loop (R22's generator case). */
  class Generated extends Fix:
    val state = Fsm(Four, init = Four.A) {
      for (member, next) <- Four.values.zip(Four.values.tail :+ Four.values.head) do
        in(member) { goto(next) }
    }

  def emitted(m: Module): String =
    new Emission().emit(m.elaborateDesign()).files.last._2

  /** The module body with the R20.2 header and the module's own name removed,
    * so two spellings of one design compare as hardware rather than as files. */
  def bodyOf(verilog: String): String =
    verilog.linesIterator
      .filterNot(_.trim.startsWith("//"))
      .map(_.replaceAll("""\bMachine(AsSwitch)?\b""", "M"))
      .mkString("\n")

  def fsmOf(m: Module): FsmInfo =
    m.elaborateDesign().top.fsms match
      case one :: Nil => one
      case other => throw AssertionError(s"expected exactly one machine, got ${other.length}")

  def driverFor(m: Module, name: String): List[Stmt] =
    val top = m.elaborateDesign().top
    val id = top.signals.find(_.name == name).map(_.id).getOrElse(
      throw AssertionError(s"no signal named $name"))
    top.drivers.find(_.signalId == id).map(_.stmts).getOrElse(
      throw AssertionError(s"no driver block for $name"))

  def onlySwitch(m: Module, name: String): Stmt.Switch =
    driverFor(m, name).collect { case s: Stmt.Switch => s } match
      case one :: Nil => one
      case other => throw AssertionError(s"expected exactly one switch, got ${other.length}")

  def litOf(e: Expr): Int = e match
    case Expr.Lit(v, _, _) => v.toInt
    case other => throw AssertionError(s"expected a literal, got $other")

  def stripLocs(s: Stmt): Stmt = s match
    case Stmt.Assign(t, e, b, _) => Stmt.Assign(t, e, b, Loc.NoLoc)
    case Stmt.When(c, t, e, _) => Stmt.When(c, t.map(stripLocs), e.map(stripLocs), Loc.NoLoc)
    case Stmt.Switch(kind, selector, cases, defaults, _) =>
      Stmt.Switch(kind, selector,
        cases.map(c => SwitchCase(c.values, c.stmts.map(stripLocs), Loc.NoLoc)),
        defaults.map(_.map(stripLocs)), Loc.NoLoc)
    case other => other

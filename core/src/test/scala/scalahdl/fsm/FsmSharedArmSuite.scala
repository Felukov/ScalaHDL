package scalahdl.fsm

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}
import scalahdl.emit.Emission

/** R25.1 — an arm may name several states.
  *
  * R22 has given one body to several values since it was written; R25 gave an
  * arm exactly one state, so a machine whose two steps differ in a detail had
  * to either duplicate the body or ask *which state am I* inside a body that
  * claimed they were the same. This is R22's alias list read as a graph.
  *
  * The two claims worth testing hardest are at opposite ends. **The emitted
  * Verilog is the alias list's**: one case item, both labels, in the order
  * written — which is R25's whole bargain, that a machine is a switch and
  * nothing more. **The graph is not**: a shared arm is not one edge from a
  * pair, it is one edge from each state, so every check R25 makes about a
  * state — a way out, reachability — is unchanged by how the arm was spelled.
  */
class FsmSharedArmSuite extends munit.FunSuite:
  import FsmFixtures.*
  import FsmGraphFixtures.{fsmReport, ref}
  import FsmSharedArmFixtures.*

  private def diagnostic(code: Code)(body: => Module): Diagnostic =
    val errs = intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics
    errs.find(_.code == code).getOrElse(
      fail(s"expected $code, got ${errs.map(d => d.code -> d.summary)}"))

  /** The module body with the header and the module's own name removed, so the
    * two spellings of one machine compare as hardware rather than as files. */
  private def body(m: Module): String =
    emitted(m).linesIterator
      .filterNot(_.trim.startsWith("//"))
      .map(_.replaceAll("""\bShared(AsSwitch)?\b""", "S"))
      .mkString("\n")

  // ---- the emission: R22's alias list, unchanged --------------------------

  test("a shared arm is one case item carrying every label, in order"):
    val sw = onlySwitch(new Shared, "step")
    assertEquals(sw.cases.map(_.values.map(litOf)),
      List(List(0), List(1, 2), List(3)),
      "the shared arm is one alternative, not two")

  test("emits the same Verilog as the R22 switch it stands for"):
    // The load-bearing test, and the same one R25 makes for a single-state
    // arm: `in(a, b)` is `is(a, b)` and there is nothing else to it. The
    // reference reads its register by name where the machine reads it as
    // `currentState`, which is the same signal and emits as the same word.
    assertEquals(body(new Shared), body(new SharedAsSwitch))

  test("the emitted case item names both states"):
    val verilog = emitted(new Shared)
    assert(verilog.contains("2'd1, 2'd2:"), verilog)

  // ---- the graph: one edge per state --------------------------------------

  test("a `goto` in a shared arm records an edge from each state"):
    val fsm = fsmOf(new Shared)
    val out = fsm.transitions.filter(_.to == BigInt(3))
    assertEquals(out.map(_.from), List(Some(BigInt(1)), Some(BigInt(2))),
      "one edge per state that selects the arm, in the order they were named")

  test("the edges of a shared `goto` share its guard and its source line"):
    val s = new Shared
    val fsm = fsmOf(s)
    val out = fsm.transitions.filter(_.to == BigInt(3))
    assertEquals(out.map(_.guard).distinct.length, 1, "one guard, read once")
    assertEquals(out.head.guard, Some(s.ref("done")))
    assertEquals(out.map(_.loc).distinct.length, 1, "one `goto`, one location")

  test("each state of a shared arm is its own state of the machine"):
    val fsm = fsmOf(new Shared)
    assertEquals(fsm.states.map(_.code), List(BigInt(0), BigInt(1), BigInt(2), BigInt(3)))
    assertEquals(fsm.states.map(_.name),
      List("Quad.A", "Quad.B", "Quad.C", "Quad.D"))

  test("`stay` in a shared arm records a self-loop for each of them"):
    val fsm = fsmOf(new SharedHold)
    val loops = fsm.transitions.filter(_.selfLoop)
    assertEquals(loops.map(_.from), List(Some(BigInt(1)), Some(BigInt(2))))

  test("a `goto` to one of the arm's own states is a self-loop only for that one"):
    // `in(B, C) { goto(B) }` leaves B holding and moves C to B, which is two
    // different facts about one statement and is why the flag is per edge.
    val fsm = fsmOf(new SharedSelf)
    val toB = fsm.transitions.filter(_.to == BigInt(1))
    assertEquals(toB.map(t => t.from -> t.selfLoop),
      List(Some(BigInt(1)) -> true, Some(BigInt(2)) -> false))

  test("the report draws an edge from every state of a shared arm"):
    val design = (new Shared).elaborateDesign()
    val report = new Emission().emit(design).reports
      .collectFirst { case (name, text) if name.endsWith(".fsm.md") => text }
      .getOrElse(fail("no FSM report"))
    assert(report.contains("B --> D"), report)
    assert(report.contains("C --> D"), report)

  // ---- the checks, unchanged by the spelling ------------------------------

  test("a shared arm gives every state it names a way out"):
    (new Shared).elaborateDesign()

  test("a state whose only exit is a self-loop still has no way out"):
    // `in(B, C) { goto(B) }` is an exit for C and not for B, and the check
    // reads the edges rather than the arm.
    val d = diagnostic(Code.FSM_STATE_NO_EXIT) {
      new Fix:
        val step = Fsm(Quad, init = Quad.A) {
          in(Quad.A) { goto(Quad.B) }
          in(Quad.B, Quad.C) { goto(Quad.B) }
          terminal(Quad.D)
        }
    }
    assert(d.summary.contains("Quad.B"), d.summary)

  test("a state reachable only as an alias of a reached arm is still unreachable"):
    // Nothing ever `goto`s C: it shares B's body, which is not the same as
    // being entered. The alias list is about the hardware's dispatch, not
    // about the graph.
    val d = diagnostic(Code.FSM_STATE_UNREACHABLE) {
      new Fix:
        val step = Fsm(Quad, init = Quad.A) {
          in(Quad.A) { goto(Quad.B) }
          in(Quad.B, Quad.C) { goto(Quad.A) }
          terminal(Quad.D)
        }
    }
    assert(d.summary.contains("Quad.C"), d.summary)

  // ---- the ways it can be written wrong -----------------------------------

  test("`in()` naming no state is refused"):
    val d = diagnostic(Code.SWITCH_EMPTY_ARM) {
      new Fix:
        val step = Fsm(Quad, init = Quad.A) {
          in() { goto(Quad.B) }
        }
    }
    assert(d.summary.contains("`in()`"), d.summary)
    assert(d.fix.exists(_.contains("name at least one state")), d.fix.toString)

  test("one arm naming a state twice is the duplicate-state error"):
    val d = diagnostic(Code.FSM_DUPLICATE_STATE) {
      new Fix:
        val step = Fsm(Quad, init = Quad.A) {
          in(Quad.A) { goto(Quad.B) }
          in(Quad.B, Quad.B) { goto(Quad.A) }
        }
    }
    assert(d.summary.contains("Quad.B"), d.summary)

  test("a state already given an arm may not be aliased into a later one"):
    val d = diagnostic(Code.FSM_DUPLICATE_STATE) {
      new Fix:
        val step = Fsm(Quad, init = Quad.A) {
          in(Quad.A) { goto(Quad.B) }
          in(Quad.B) { goto(Quad.A) }
          in(Quad.C, Quad.B) { goto(Quad.A) }
        }
    }
    assert(d.related.nonEmpty, "the first arm is related from the second")
    assert(d.fix.exists(_.contains("in(a, b)")), d.fix.toString)

  test("a foreign member in an alias list is caught like any other"):
    val d = diagnostic(Code.FSM_FOREIGN_STATE) {
      new Fix:
        val step = Fsm(Quad, init = Quad.A) {
          in(Quad.A, Light.On) { goto(Quad.B) }
        }
    }
    assert(d.summary.contains("Light.On"), d.summary)

  // ---- `currentState`, which is what makes a shared arm usable ------------

  test("`currentState` is the machine's own register, not a copy of it"):
    val design = (new Shared).elaborateDesign()
    val step = design.top.signals.find(_.name == "step").getOrElse(fail("no `step`"))
    val guard = fsmOf(new Shared).transitions
      .find(t => t.from.contains(BigInt(1)) && t.to == BigInt(2))
      .flatMap(_.guard).getOrElse(fail("no B -> C edge"))
    assertEquals(guard, Expr.BinOp("==", Expr.Ref(step.id), Expr.uint(1, 2)),
      "the guard reads the register the machine declared")

  test("`currentState` outside a machine is a diagnostic, not a type error"):
    val d = diagnostic(Code.FSM_SCOPE_CONTEXT) {
      new Fix:
        val x = Out(Bool())
        x := currentState === Quad.A
    }
    assert(d.summary.contains("currentState"), d.summary)

  test("`currentState` may not be assigned, exactly as the `val` may not"):
    val d = diagnostic(Code.FSM_STATE_ASSIGNED) {
      new Fix:
        val step = Fsm(Quad, init = Quad.A) {
          in(Quad.A) { currentState := Quad.B }
          in(Quad.B, Quad.C) { goto(Quad.A) }
          terminal(Quad.D)
        }
    }
    assert(d.fix.exists(_.contains("goto")), d.fix.toString)

object FsmSharedArmFixtures:
  import FsmFixtures.*

  object Quad extends Encoding(2):
    val A, B, C, D = Value

  /** Two steps that share a body and differ inside it — the shape a ported
    * decoder has, where the arm is one and the states are two. */
  class Shared extends Fix:
    val go = In(Bool())
    val done = In(Bool())

    val step = Fsm(Quad, init = Quad.A) {
      in(Quad.A) { goto(Quad.B) }
      in(Quad.B, Quad.C) {
        when(currentState === Quad.B) { goto(Quad.C) }
        when(done) { goto(Quad.D) }
      }
      terminal(Quad.D)
    }

  /** The same machine as an R22 switch on a plain register. */
  class SharedAsSwitch extends Fix:
    val go = In(Bool())
    val done = In(Bool())

    val step = Reg(Quad(), init = Quad.A)

    switch(step) {
      is(Quad.A) { step := Quad.B }
      is(Quad.B, Quad.C) {
        when(step === Quad.B) { step := Quad.C }
        when(done) { step := Quad.D }
      }
      is(Quad.D) {}
    }

  class SharedHold extends Fix:
    val done = In(Bool())
    val step = Fsm(Quad, init = Quad.A) {
      in(Quad.A) {
        goto(Quad.B)
        when(done) { goto(Quad.C) }
      }
      in(Quad.B, Quad.C) {
        when(done) { goto(Quad.D) }
        stay()
      }
      terminal(Quad.D)
    }

  class SharedSelf extends Fix:
    val done = In(Bool())
    val step = Fsm(Quad, init = Quad.A) {
      in(Quad.A) { goto(Quad.C) }
      in(Quad.B, Quad.C) {
        goto(Quad.B)
        when(done) { goto(Quad.D) }
      }
      terminal(Quad.D)
    }

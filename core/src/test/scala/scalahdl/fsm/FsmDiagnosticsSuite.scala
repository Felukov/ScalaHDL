package scalahdl.fsm

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}

/** R25/R19 — every structural way a declared machine can be wrong.
  *
  * These are the reason R25 exists rather than a style guide: each of them is
  * a real defect that the assembled form — a register, a switch, and some
  * assignments — has no way to notice. Each test names a stable `Code` rather
  * than matching prose. */
class FsmDiagnosticsSuite extends munit.FunSuite:
  import FsmFixtures.*
  import FsmDiagnosticsFixtures.*

  private def diagnostic(code: Code)(body: => Module): Diagnostic =
    val errs = intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics
    errs.find(_.code == code).getOrElse(
      fail(s"expected $code, got ${errs.map(d => d.code -> d.summary)}"))

  test("`goto` outside a machine is a diagnostic, not a type error"):
    val d = diagnostic(Code.FSM_SCOPE_CONTEXT) {
      new Fix:
        val state = Fsm(Light, init = Light.Off) {
          in(Light.Off) { goto(Light.On) }
          in(Light.On) { goto(Light.Broken) }
          terminal(Light.Broken)
        }
        goto(Light.On)
    }
    assert(d.summary.contains("`goto`"), d.summary)
    assert(d.fix.exists(_.contains("Fsm")), d.fix.toString)

  test("`in` outside a machine names the construct it belongs to"):
    val d = diagnostic(Code.FSM_SCOPE_CONTEXT) {
      new Fix:
        val x = Out(Bool())
        x := 0
        in(Light.Off) { goto(Light.On) }
    }
    assert(d.primaryLoc != Loc.NoLoc, "the call site is a real position")

  test("two arms for one state name both"):
    val d = diagnostic(Code.FSM_DUPLICATE_STATE) {
      new Fix:
        val state = Fsm(Light, init = Light.Off) {
          in(Light.Off) { goto(Light.On) }
          in(Light.On) { goto(Light.Off) }
          in(Light.Off) { goto(Light.Broken) }
          terminal(Light.Broken)
        }
    }
    assert(d.related.exists(_.loc.line < d.primaryLoc.line),
      s"the first arm is related from the second: ${d.related}")

  test("a state declared twice, once as `in` and once as `terminal`, is still duplicate"):
    val d = diagnostic(Code.FSM_DUPLICATE_STATE) {
      new Fix:
        val state = Fsm(Light, init = Light.Off) {
          in(Light.Off) { goto(Light.On) }
          in(Light.On) { goto(Light.Off) }
          terminal(Light.On)
          terminal(Light.Broken)
        }
    }
    assert(d.fix.exists(_.contains("once")), d.fix.toString)

  test("an encoding member with no arm is a state nothing was said about"):
    val d = diagnostic(Code.FSM_STATE_UNHANDLED) {
      new Fix:
        val state = Fsm(Light, init = Light.Off) {
          in(Light.Off) { goto(Light.On) }
          in(Light.On) { goto(Light.Off) }
        }
    }
    assert(d.summary.contains("Light.Broken"), d.summary)
    assert(d.fix.exists(_.contains("terminal(Light.Broken)")), d.fix.toString)

  test("a non-terminal state with no way out is an error, and `terminal` is the fix"):
    val d = diagnostic(Code.FSM_STATE_NO_EXIT) {
      new Fix:
        val state = Fsm(Light, init = Light.Off) {
          in(Light.Off) { goto(Light.On) }
          in(Light.On) { goto(Light.Broken) }
          in(Light.Broken) {}
        }
    }
    assert(d.summary.contains("Light.Broken"), d.summary)
    assert(d.fix.exists(_.contains("terminal")), d.fix.toString)

  test("a conditional exit is an exit"):
    // The check asks whether a way out is written, not whether it fires.
    (new Fix:
      val go = In(Bool())
      val state = Fsm(Light, init = Light.Off) {
        in(Light.Off) { when(go) { goto(Light.On) } }
        in(Light.On) { when(go) { goto(Light.Off) } }
        terminal(Light.Broken)
        default { goto(Light.Broken) }
      }
    ).elaborateDesign()

  test("a state unreachable from the initial state is an error"):
    val d = diagnostic(Code.FSM_STATE_UNREACHABLE) {
      new Fix:
        val state = Fsm(Light, init = Light.Off) {
          in(Light.Off) { goto(Light.On) }
          in(Light.On) { goto(Light.Off) }
          terminal(Light.Broken)
        }
    }
    assert(d.summary.contains("Light.Broken"), d.summary)

  test("unreachability is transitive, so an island of states is caught"):
    val d = diagnostic(Code.FSM_STATE_UNREACHABLE) {
      new Fix:
        // C and D only reach each other: no edge from A or B ever arrives.
        val state = Fsm(Quad, init = Quad.A) {
          in(Quad.A) { goto(Quad.B) }
          in(Quad.B) { goto(Quad.A) }
          in(Quad.C) { goto(Quad.D) }
          in(Quad.D) { goto(Quad.C) }
        }
    }
    assert(d.summary.contains("Quad.C") || d.summary.contains("Quad.D"), d.summary)

  test("a state reached only from the `default` arm is reachable"):
    // The machine can hold a value no arm names — that is what the arm is for.
    (new Fix:
      val state = Fsm(Light, init = Light.Off) {
        in(Light.Off) { goto(Light.On) }
        in(Light.On) { goto(Light.Off) }
        terminal(Light.Broken)
        default { goto(Light.Broken) }
      }
    ).elaborateDesign()

  test("`goto` to another encoding's member is caught"):
    val d = diagnostic(Code.FSM_FOREIGN_STATE) {
      new Fix:
        val state = Fsm(Light, init = Light.Off) {
          in(Light.Off) { goto(Quad.B) }
          in(Light.On) { goto(Light.Off) }
          terminal(Light.Broken)
        }
    }
    assert(d.summary.contains("Quad.B"), d.summary)

  test("an initial state from another encoding is caught at the declaration"):
    val d = diagnostic(Code.FSM_FOREIGN_STATE) {
      new Fix:
        val state = Fsm(Light, init = Quad.A) {
          in(Light.Off) { goto(Light.On) }
        }
    }
    assert(d.summary.contains("initial state"), d.summary)

  test("the state register may not be assigned outside its machine"):
    val d = diagnostic(Code.FSM_STATE_ASSIGNED) {
      new Fix:
        val panic = In(Bool())
        val state = Fsm(Light, init = Light.Off) {
          in(Light.Off) { goto(Light.On) }
          in(Light.On) { goto(Light.Off) }
          terminal(Light.Broken)
          default { goto(Light.Broken) }
        }
        when(panic) { state := Light.Broken }
    }
    assert(d.fix.exists(_.contains("goto")), d.fix.toString)
    assert(d.related.nonEmpty, "the machine's declaration is related")

  test("a machine declared inside a machine is caught"):
    val d = diagnostic(Code.FSM_SCOPE_CONTEXT) {
      new Fix:
        val outer = Fsm(Light, init = Light.Off) {
          in(Light.Off) {
            val inner = Fsm(Quad, init = Quad.A) {
              in(Quad.A) { goto(Quad.B) }
            }
          }
        }
    }
    assert(d.summary.contains("inside"), d.summary)

object FsmDiagnosticsFixtures:
  object Quad extends Encoding(2):
    val A, B, C, D = Value

package scalahdl.select

import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.switchcase.SwitchFixtures.*

/** R26/R11 — a construct selecting on conditions is exhaustive only with a
  * `default`.
  *
  * R22 can call a switch exhaustive by counting: name every value of a two-bit
  * selector and there is nothing left. Conditions have no such space. "These
  * cubes partition the surface" is a fact about the *design* — true of the
  * m68k decoder, and not something the construct knows — so `default` is how
  * it gets said. */
class SelectCompletenessSuite extends munit.FunSuite:

  private def codesOf(m: => Module): List[Code] =
    try
      val _ = m.elaborateDesign()
      Nil
    catch case v: ValidationFailed => v.diagnostics.map(_.code)

  test("a combinational target left to the conditions is an uncovered path") {
    class NoDefault extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      priorityCase {
        on(a === 1.U(4)) { out := 1.U(8) }
        on(a === 2.U(4)) { out := 2.U(8) }
      }
    assert(codesOf(new NoDefault).contains(Code.COMBINATIONAL_INCOMPLETE), codesOf(new NoDefault).toString)
  }

  test("a default covers it, and so does an assignment before the construct") {
    class WithDefault extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      priorityCase {
        on(a === 1.U(4)) { out := 1.U(8) }
        default { out := 0.U(8) }
      }
    assertEquals(codesOf(new WithDefault), Nil)

    class Predefaulted extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      out := 0.U(8)                       // R11's default-before-conditional
      priorityCase {
        on(a === 1.U(4)) { out := 1.U(8) }
      }
    assertEquals(codesOf(new Predefaulted), Nil)
  }

  test("naming both values of a one-bit condition still does not make it exhaustive") {
    // The degenerate case the counting rule would have called covered: two
    // constant conditions over a one-bit selector. R26 does not count.
    class BothConstants extends Fix:
      val out = Out(UInt(8))
      priorityCase {
        on(True)  { out := 1.U(8) }
        on(False) { out := 2.U(8) }
      }
    assert(codesOf(new BothConstants).contains(Code.COMBINATIONAL_INCOMPLETE))
  }

  test("a register holds where no condition takes, exactly as R11.1 says") {
    class Held extends Fix:
      val a = In(UInt(4))
      val r = Reg(UInt(8), init = 0.U(8))
      priorityCase {
        on(a === 1.U(4)) { r := 1.U(8) }
      }
    assertEquals(codesOf(new Held), Nil)
  }

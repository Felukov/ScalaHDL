package scalahdl.select

import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.switchcase.SwitchFixtures.*

/** R26/R19 — every way selection on conditions can be wrong, and what it says.
  *
  * The two that matter are `parallelCase`'s: it is the form that *claims*
  * something, and these are what deciding the claim can find. An arm R26
  * cannot read as a cube is refused rather than trusted, which is what keeps
  * the construct a proof instead of a promise. */
class SelectDiagnosticsSuite extends munit.FunSuite:

  private def errorsOf(m: => Module): List[scalahdl.diag.Diagnostic] =
    try
      val _ = m.elaborateDesign()
      Nil
    catch case v: ValidationFailed => v.diagnostics

  private def codesOf(m: => Module): List[Code] = errorsOf(m).map(_.code)

  test("a condition wider than one bit is refused") {
    class Wide extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      out := 0.U(8)
      priorityCase {
        on(a) { out := 1.U(8) }
        default { out := 0.U(8) }
      }
    val d = errorsOf(new Wide)
    assertEquals(d.map(_.code), List(Code.SELECT_CONDITION_WIDTH))
    assert(d.head.summary.contains("4-bit"), d.head.summary)
    assert(d.head.fix.exists(_.contains("on(x === y)")), d.head.fix.toString)
  }

  test("a parallelCase arm that is not a masked compare is refused, not trusted") {
    class NotACube extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val out = Out(UInt(8))
      out := 0.U(8)
      parallelCase {
        on(a > b) { out := 1.U(8) }
        default { out := 0.U(8) }
      }
    val d = errorsOf(new NotACube)
    assertEquals(d.map(_.code), List(Code.SELECT_ARM_NOT_CUBE))
    assert(d.head.fix.exists(_.contains("priorityCase")), d.head.fix.toString)
  }

  test("priorityCase claims nothing, so it takes the same arm happily") {
    class Fine extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val out = Out(UInt(8))
      out := 0.U(8)
      priorityCase {
        on(a > b) { out := 1.U(8) }
        default { out := 0.U(8) }
      }
    assertEquals(codesOf(new Fine), Nil)
  }

  test("two arms that match the same value are refused, naming the value") {
    class Overlap extends Fix:
      val opcode = In(UInt(8))
      val out = Out(UInt(8))
      out := 0.U(8)
      parallelCase {
        on((opcode & 0xF0.U(8)) === 0x40.U(8)) { out := 1.U(8) }
        on((opcode & 0xFC.U(8)) === 0x4C.U(8)) { out := 2.U(8) }
        default { out := 0.U(8) }
      }
    val d = errorsOf(new Overlap)
    assertEquals(d.map(_.code), List(Code.SELECT_ARMS_OVERLAP))
    // 0x4C is in both, and the reader is shown it rather than left to find it.
    assert(d.head.summary.contains("0x4c"), d.head.summary)
    assert(d.head.related.exists(_.note.contains("first arm")), d.head.related.toString)
  }

  test("arms over different selectors cannot be compared, and say so") {
    class TwoSelectors extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val out = Out(UInt(8))
      out := 0.U(8)
      parallelCase {
        on((a & 0xF0.U(8)) === 0x40.U(8)) { out := 1.U(8) }
        on((b & 0xF0.U(8)) === 0x80.U(8)) { out := 2.U(8) }
        default { out := 0.U(8) }
      }
    val d = errorsOf(new TwoSelectors)
    assertEquals(d.map(_.code), List(Code.SELECT_ARM_NOT_CUBE))
    assert(d.head.summary.contains("2 different expressions"), d.head.summary)
  }

  test("`is` inside a condition construct, and `on` inside a switch, name the other one") {
    class IsInSelect extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      out := 0.U(8)
      priorityCase {
        is(1.U(4)) { out := 1.U(8) }
      }
    val a = intercept[ValidationFailed]((new IsInSelect).elaborateDesign()).diagnostics
    assertEquals(a.map(_.code), List(Code.SWITCH_ARM_CONTEXT))
    assert(a.head.fix.exists(_.contains("on(cond)")), a.head.fix.toString)

    class OnInSwitch extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      out := 0.U(8)
      switch(a) {
        on(a === 1.U(4)) { out := 1.U(8) }
      }
    val b = intercept[ValidationFailed]((new OnInSwitch).elaborateDesign()).diagnostics
    assertEquals(b.map(_.code), List(Code.SWITCH_ARM_CONTEXT))
    assert(b.head.fix.exists(_.contains("priorityCase")), b.head.fix.toString)
  }

  test("`on()` naming nothing selects nothing") {
    class Empty extends Fix:
      val out = Out(UInt(8))
      out := 0.U(8)
      priorityCase {
        on() { out := 1.U(8) }
      }
    val d = intercept[ValidationFailed]((new Empty).elaborateDesign()).diagnostics
    assertEquals(d.map(_.code), List(Code.SWITCH_EMPTY_ARM))
  }

  test("two labels of one `on` may overlap — an arm is an or, not a conflict") {
    // A cube list spells an inequality by covering it in pieces, and the
    // pieces of one shape are allowed to meet. The claim is that two *arms*
    // cannot both hold, which is a different sentence.
    class SameArm extends Fix:
      val opcode = In(UInt(8))
      val out = Out(UInt(8))
      out := 0.U(8)
      parallelCase {
        on((opcode & 0xF0.U(8)) === 0x40.U(8),
           (opcode & 0xFC.U(8)) === 0x4C.U(8)) { out := 1.U(8) }
        on((opcode & 0xF0.U(8)) === 0x80.U(8)) { out := 2.U(8) }
        default { out := 0.U(8) }
      }
    assertEquals(codesOf(new SameArm), Nil)
  }

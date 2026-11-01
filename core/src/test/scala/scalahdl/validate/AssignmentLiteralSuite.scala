package scalahdl.validate

import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, param}
import scalahdl.emit.{Emission, VerilogEmitter}

/** R06.3 — a literal that is the whole right-hand side takes the target's
  * width, and nothing else changes.
  *
  * The suite is deliberately half negative: the rule earns its keep only if
  * every position R6.1 and R21.7 already settled still reports what it did, so
  * each of those is asserted here next to the case that now works.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M17-assignment-literals/R06.3-whole-rhs-literal-width.md`. */
class AssignmentLiteralSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  private def emit(body: => Module): String =
    val m = body
    new VerilogEmitter("0.1.1").emitModule(m.elaborateDesign().top, m.moduleName)

  private def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  // ---- the rule --------------------------------------------------------

  test("a whole-RHS literal takes the target's width"):
    class Good extends Fix:
      val q = Out(UInt(5))
      q := 2.U
    assert(emit(new Good).contains("5'd2"), emit(new Good))

  test("a register's init takes the register's declared width"):
    class Good extends Fix:
      val counter = Reg(UInt(32), init = 0.U)
      val q = Out(UInt(32))
      q := counter
    val v = emit(new Good)
    assert(v.contains("32'd0"), v)

  test("a signed init resolves and still emits its sign outside the constant"):
    // R21.7's emission rule is unaffected by where the width came from.
    class Good extends Fix:
      val level = Reg(SInt(8), init = (-1).S)
      val q = Out(SInt(8))
      q := level
    val v = emit(new Good)
    assert(v.contains("-8'sd1"), v)
    assert(!v.contains("'sd-"), v)

  test("the width is the leaf's, for a bundle field and a vec element"):
    case class Entry(w: Int) extends Bundle:
      val addr = UInt(w)
      val prot = Bits(3)
    class Good extends Fix:
      val entry = Reg(Entry(12), init = null)
      val slots = Reg(Vec(4, UInt(9)), init = null)
      val q = Out(UInt(12))
      entry.addr := 1.U       // the field's 12, not the bundle's packed 15
      entry.prot := 0         // R6.4 — a bare Int is the only literal a Bits takes
      slots(2) := 3.U         // the element's 9, not the Vec's 36
      q := entry.addr
    val v = emit(new Good)
    assert(v.contains("12'd1"), v)
    assert(v.contains("3'd0"), v)
    assert(v.contains("9'd3"), v)

  test("a child instance's input port supplies the width of its connection"):
    class Adder(width: Int) extends Module(param("w", width)):
      val a = In(UInt(width))
      val sum = Out(UInt(width))
      val b = In(UInt(width))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      sum := a + b
    class Good extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val step = In(UInt(8))
      val total = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val adder = inst(new Adder(8))
      adder.a := step
      adder.b := 1.U
      total := adder.sum
    val v = new Emission().emit((new Good).elaborateDesign()).files.last._2
    assert(v.contains(".b(8'd1)"), v)

  test("a connection's nested literal resolves too"):
    // Connections bypass `addAssign`, so before R6.3 they were never resolved
    // at all and this emitted `1'b1` — an expression whose value depended on
    // the context it landed in, which R21.6's invariant forbids.
    class Adder(width: Int) extends Module(param("w", width)):
      val a = In(UInt(width))
      val sum = Out(UInt(width))
      val b = In(UInt(width))
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      sum := a + b
    class Good extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val step = In(UInt(8))
      val total = Out(UInt(8))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val adder = inst(new Adder(8))
      adder.a := step + 1.U
      adder.b := step
      total := adder.sum
    val v = new Emission().emit((new Good).elaborateDesign()).files.last._2
    assert(v.contains("8'd1"), v)
    assert(!v.contains("1'b1"), v)

  // ---- the guard, where it still does its job --------------------------

  test("a nested literal still takes its sibling's width, not the target's"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(16))
      q := a + 1.U
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("width-8"), msg)
    assert(msg.contains("width 16"), msg)

  test("R6.3 does not descend into a Mux, a Cat, or a Fill"):
    class BadMux extends Fix:
      val s = In(Bool()); val q = Out(UInt(5))
      q := Mux(s, 1.U, 2.U)
    class BadCat extends Fix:
      val a = In(UInt(4)); val q = Out(UInt(5))
      q := Cat(a, 1.U)
    class BadFill extends Fix:
      val q = Out(UInt(4))
      q := Fill(2, 1.U)
    List[() => Module](() => new BadMux, () => new BadCat, () => new BadFill).foreach { make =>
      val msg = expectFailure(make()).map(_.render).mkString
      // R6.1's uninferrable literal for the Mux arms, R21.5's width-known part
      // for the other two — each still reports the rule that owns it.
      assert(msg.contains("no inferrable width") || msg.contains("must be width-known"), msg)
    }

  test("an over-range whole-RHS literal reports the range, not uninferrability"):
    class Bad extends Fix:
      val q = Out(UInt(5))
      q := 300.U
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("300"), msg)
    assert(msg.contains("9"), s"should name the required width: $msg")
    assert(msg.contains("5"), s"should name the available width: $msg")
    assert(!msg.contains("no inferrable width"), msg)

  test("a suffixed literal never adopts the target's kind"):
    // R21.7 — `.U`/`.S` fix the kind at the literal, and R6.3 fills in only what
    // the literal is missing. The target supplies the width, so the mismatch is
    // reported by R6.5 naming both types rather than as an uninferrable width,
    // which is what it degraded to before the assignment checked kinds.
    class Bad extends Fix:
      val q = Out(UInt(5))
      q := 2.S
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("cannot assign SInt"), msg)
    assert(msg.contains("no implicit kind conversion"), msg)

  test("an aggregate target offers a literal no width"):
    case class Pair(w: Int) extends Bundle:
      val a = UInt(w)
      val b = UInt(w)
    class Bad extends Fix:
      val p = Reg(Pair(4), init = null)
      val q = Out(UInt(4))
      p := 0.U
      q := p.a
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("no inferrable width"), msg)

  // ---- R20.1: this stayed a surface change -----------------------------

  test("removing the annotations emits byte-identical Verilog"):
    class Annotated extends Fix:
      val counter = Reg(UInt(8), init = 0.U(8))
      val q = Out(UInt(8))
      val flag = Out(Bool())
      counter := counter + 1.U(8)
      q := 7.U(8)
      flag := 0.U(1)
    class Inferred extends Fix:
      val counter = Reg(UInt(8), init = 0.U)
      val q = Out(UInt(8))
      val flag = Out(Bool())
      counter := counter + 1.U
      q := 7.U
      flag := 0.U
    // The two spellings must differ in nothing but their module name, which is
    // the class name and therefore has to differ for them to be two classes.
    def body(v: String, name: String): String =
      v.linesIterator.drop(3).mkString("\n").replace(name, "M")
    assertEquals(
      body(emit(new Inferred), "Inferred"),
      body(emit(new Annotated), "Annotated"))

package scalahdl.expr

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic}
import scalahdl.emit.VerilogEmitter

/** R21.1 / R21.2 / R21.3 / R21.6 — shifts, ordering comparisons, the full
  * product, and the rule that ties them together: signedness is applied per
  * operation with `$signed`, never inherited from a declaration.
  *
  * Each test maps to a checkbox in `roadmap/open/M13-cpu-expressions/`
  * (`R21.1-shifts.md`, `R21.2-comparisons.md`, `R21.3-multiply.md`,
  * `R21.6-signed-emission.md`). The emitted strings are asserted exactly: a
  * future emitter change that drops one `$signed` wrapper would leave Verilog
  * silently evaluating the whole expression unsigned, which no
  * elaboration-level check can see. */
class SignedEmissionSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def emit(body: => Module): String =
    val m = body
    val design = m.elaborateDesign()
    new VerilogEmitter("0.1.2").emitModule(design.top, m.moduleName)

  def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  // ---- R21.1: shifts ---------------------------------------------------

  test("u >> n and u << n elaborate with no width error and emit plainly"):
    class Good extends Fix:
      val u = In(UInt(32)); val n = In(UInt(5))
      val r = Out(UInt(32)); val l = Out(UInt(32))
      r := u >> n              // SRL
      l := u << n              // SLL
    val v = emit(new Good)
    assert(v.contains("(u >> n)"), v)
    assert(v.contains("(u << n)"), v)

  test("s >>> n emits $signed(($signed(s) >>> n)); s >> n stays a logical shift"):
    class Good extends Fix:
      val s = In(SInt(32)); val n = In(UInt(5))
      val ar = Out(SInt(32)); val lo = Out(SInt(32))
      ar := s >>> n            // SRA
      lo := s >> n             // the two's-complement pattern, zero fill
    val v = emit(new Good)
    // Both wrappers are load-bearing. The inner one makes the operand signed;
    // the outer one keeps the *result* signed where the shift is nested in an
    // expression with an unsigned operand, because Verilog would otherwise
    // evaluate the whole expression unsigned and reach down into this operand,
    // turning the `SRA` into an `SRL`. `ExpressionOracleSuite` catches the
    // missing outer wrapper in simulation; this catches it in the text.
    assert(v.contains("$signed(($signed(s) >>> n))"),
      s"an arithmetic shift must be signed inside and out: $v")
    assert(v.contains("(s >> n)"), s"logical shift must not be signed: $v")
    assert(!v.contains("$signed((s >> n))"),
      s"a logical shift must not acquire a wrapper: $v")

  test("u >>> n errors naming .asSInt"):
    class Bad extends Fix:
      val u = In(UInt(32)); val n = In(UInt(5))
      val q = Out(UInt(32))
      q := u >>> n
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("`>>>` is not defined on `UInt`"), msg)
    assert(msg.contains(".asSInt"), s"the fix must name .asSInt: $msg")

  test("the shift amount escapes width agreement and may be any width"):
    class Good extends Fix:
      val u = In(UInt(32))
      val wide = In(UInt(16))       // far wider than log2(32)
      val one = In(Bool())          // a 1-bit unsigned amount
      val q = Out(UInt(32)); val r = Out(UInt(32))
      q := u >> wide
      r := u << one
    val v = emit(new Good)
    assert(v.contains("(u >> wide)") && v.contains("(u << one)"), v)

  test("an SInt shift amount is an error"):
    class Bad extends Fix:
      val u = In(UInt(32)); val n = In(SInt(5))
      val q = Out(UInt(32))
      q := u >> n
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("shift amount is SInt"), msg)

  test("a shift result keeps the left operand's width, not the amount's"):
    class Good extends Fix:
      val u = In(UInt(32)); val n = In(UInt(5))
      val q = Out(UInt(32))
      q := u << n            // width 32, so the 32-bit target agrees
    assert(emit(new Good).contains("(u << n)"))

  // ---- R21.2: ordering comparisons ------------------------------------

  test("SLTU: two UInt operands compare unsigned, with no $signed anywhere"):
    class Good extends Fix:
      val a = In(UInt(32)); val b = In(UInt(32))
      val q = Out(Bool())
      q := a < b
    val v = emit(new Good)
    assert(v.contains("(a < b)"), v)
    assert(!v.contains("$signed"), s"an unsigned compare wraps nothing: $v")

  test("SLT: a signed comparison wraps BOTH operands in $signed"):
    class Good extends Fix:
      val a = In(SInt(32)); val b = In(SInt(32))
      val q = Out(Bool())
      q := a < b
    val v = emit(new Good)
    assert(v.contains("($signed(a) < $signed(b))"),
      s"both operands must be wrapped — one wrapper alone evaluates unsigned: $v")

  test("all four orderings are signed by type"):
    class Good extends Fix:
      val a = In(SInt(8)); val b = In(SInt(8))
      val lt = Out(Bool()); val le = Out(Bool())
      val gt = Out(Bool()); val ge = Out(Bool())
      lt := a < b
      le := a <= b
      gt := a > b
      ge := a >= b
    val v = emit(new Good)
    List("<", "<=", ">", ">=").foreach { op =>
      assert(v.contains(s"($$signed(a) $op $$signed(b))"),
        s"`$op` must emit both wrappers: $v")
    }

  test("comparing width-mismatched operands errors with both widths"):
    class Bad extends Fix:
      val a = In(UInt(8)); val b = In(UInt(12))
      val q = Out(Bool())
      q := a < b
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("8") && msg.contains("12"), msg)

  test("a comparison of expressions is admissible on both sides"):
    class Good extends Fix:
      val a = In(SInt(8)); val b = In(SInt(8)); val c = In(SInt(8))
      val q = Out(Bool())
      q := (a + b) < (b - c)
    assert(emit(new Good).contains("($signed((a + b)) < $signed((b - c)))"),
      emit(new Good))

  // ---- R21.3: multiplication -------------------------------------------

  test("UInt(32) * UInt(32) is UInt(64); storing it in 32 bits errors"):
    class Bad extends Fix:
      val a = In(UInt(32)); val b = In(UInt(32))
      val q = Out(UInt(32))
      q := a * b
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("width-64"), s"should name the product width 64: $msg")
    assert(msg.contains("width 32"), s"should name the target width 32: $msg")
    assert(msg.contains("resize(32)"), s"the fix is an explicit resize: $msg")

  test("MUL: the low half is written with an explicit resize"):
    class Good extends Fix:
      val a = In(UInt(32)); val b = In(UInt(32))
      val q = Out(UInt(32))
      q := (a * b).resize(32)
    val v = emit(new Good)
    assert(v.contains("*"), v)

  test("MULH: a product bound to a val, then bit-selected (Appendix D finding 8)"):
    class Good extends Fix:
      val a = In(UInt(32)); val b = In(UInt(32))
      val mulh = Out(UInt(32))
      val prod = Wire(UInt(64))
      prod := a * b
      mulh := prod(63, 32)
    val v = emit(new Good)
    assert(v.contains("prod[63:32]"), s"the high half is a slice of a val: $v")

  test("operand widths may differ: UInt(8) * UInt(32) is UInt(40)"):
    class Good extends Fix:
      val a = In(UInt(8)); val b = In(UInt(32))
      val q = Out(UInt(40))
      q := a * b
    val v = emit(new Good)
    assert(v.contains("*"), v)

  test("both operands are widened to w1+w2 before multiplying (unsigned)"):
    class Good extends Fix:
      val a = In(UInt(8)); val b = In(UInt(4))
      val q = Out(UInt(12))
      q := a * b
    val v = emit(new Good)
    assert(v.contains("({{4{1'b0}}, a} * {{8{1'b0}}, b})"),
      s"each operand is self-determined at the product width: $v")

  test("a signed product wraps both widened operands in $signed"):
    class Good extends Fix:
      val a = In(SInt(8)); val b = In(SInt(4))
      val q = Out(SInt(12))
      q := a * b
    val v = emit(new Good)
    assert(v.contains("($signed({{4{a[7]}}, a}) * $signed({{8{b[3]}}, b}))"),
      s"signed operands are sign-extended and wrapped: $v")

  test("a signed product needs bit-addressable operands (R21.4's rule)"):
    class Bad extends Fix:
      val a = In(SInt(8)); val b = In(SInt(8)); val c = In(SInt(8))
      val q = Out(SInt(16))
      q := (a + b) * c        // the sum is anonymous; sign extension needs a val
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("requires a named signal"), msg)
    assert(msg.contains("`val`"), s"the fix is to bind a val: $msg")

  // ---- R21.6: the invariant --------------------------------------------

  test("no emitted module declares a `signed` net"):
    class Good extends Fix:
      val a = In(SInt(16)); val b = In(SInt(16)); val n = In(UInt(4))
      val lt = Out(Bool()); val sh = Out(SInt(16)); val pr = Out(SInt(32))
      lt := a < b
      sh := a >>> n
      pr := a * b
    val v = emit(new Good)
    assert(!v.contains("signed ["), s"no declaration is signed: $v")
    assert(!v.contains("signed a") && !v.contains("wire signed"), v)
    assert(v.contains("$signed"), s"signedness is applied at the operator: $v")

  test("a product nested in a wider expression is identical to one bound to a val"):
    class Inline extends Fix:
      val a = In(UInt(8)); val b = In(UInt(8)); val c = In(UInt(16))
      val q = Out(UInt(16))
      q := (a * b) + c
    class Bound extends Fix:
      val a = In(UInt(8)); val b = In(UInt(8)); val c = In(UInt(16))
      val q = Out(UInt(16))
      val prod = Wire(UInt(16))
      prod := a * b
      q := prod + c
    // The product renders identically in both, so its value cannot depend on
    // the context it sits in (R21.6's context-independence invariant).
    val product = "({{8{1'b0}}, a} * {{8{1'b0}}, b})"
    assert(emit(new Inline).contains(product), emit(new Inline))
    assert(emit(new Bound).contains(product), emit(new Bound))

  test("mixing a signed subexpression with an unsigned one is a diagnostic"):
    class Bad extends Fix:
      val a = In(SInt(8)); val b = In(UInt(8))
      val q = Out(SInt(8))
      q := a + b
    val msg = expectFailure(new Bad).map(_.render).mkString
    assert(msg.contains("no implicit signedness conversion"), msg)

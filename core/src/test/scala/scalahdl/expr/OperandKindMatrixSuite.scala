package scalahdl.expr

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.ValidationFailed
import scalahdl.emit.VerilogEmitter

/** R21 — the operand-kind matrix, table-driven.
  *
  * `ExpressionSurfaceSuite` shows that operand-kind completeness *can* hold;
  * this suite enumerates it. Every operator in R21's table is instantiated
  * against every operand kind — a named signal, an anonymous expression, or a
  * literal — in every combination on both sides, and each cell asserts the
  * result type, the result width, and the emitted operator.
  *
  * The matrix has holes, and they are the point: a cell's expected outcome is
  * *derived* from three stated rules rather than written out by hand, so a
  * hole can only exist where a rule puts one.
  *
  *   1. R6.1/R21.7 — a literal takes its width from its sibling. Two literals
  *      have no sibling between them, and the assignment boundary does not
  *      supply one, so the pair has no inferrable width.
  *   2. R21.1/R21.7 — a shift amount is the exception to (1): it takes its own
  *      minimal width and gives none back. A literal *value* being shifted
  *      therefore has no source of width either.
  *   3. R21.3/R21.4 — a signed `*` sign-extends each operand before
  *      multiplying, which emits a bit-select, so both operands must be
  *      bit-addressable (R6.2). An anonymous expression is not, and neither is
  *      a literal.
  *
  * Every hole is asserted as a diagnostic, not merely as a failure: R19 says
  * the message names the operands and a fix that exists. */
class OperandKindMatrixSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  /** Elaborate and emit, or return the rendered diagnostics. Every failure the
    * surface can raise is caught: `ValidationFailed` from the phases, and the
    * DSL-surface errors (`BitSelectAnonymousError`, `CastError`, the `require`
    * in `Fill`) that fire before a design exists. */
  def outcome(body: => Module): Either[String, String] =
    try
      val m = body
      val design = m.elaborateDesign()
      Right(new VerilogEmitter("0.1.2").emitModule(design.top, m.moduleName))
    catch
      case e: ValidationFailed => Left(e.diagnostics.map(_.render).mkString("\n"))
      case e: RuntimeException => Left(Option(e.getMessage).getOrElse(e.toString))

  /** The three kinds every operand position admits (R21). */
  enum Kind:
    case Signal, Expression, Literal
    def label: String = this match
      case Signal => "signal"
      case Expression => "expression"
      case Literal => "literal"

  val kinds: List[Kind] = Kind.values.toList

  def signedness(signed: Boolean): String = if signed then "SInt" else "UInt"

  // ---- binary operators -------------------------------------------------

  /** One row of R21's table: how it is written, how it emits, and the result
    * type as a function of the operand width and signedness. */
  final case class BinSpec(
    scala: String,
    verilog: String,
    build: (Expr, Expr) => Expr,
    result: (Int, Boolean) => Type)

  val sameKind: (Int, Boolean) => Type =
    (w, s) => if s then Type.SInt(w) else Type.UInt(w)
  val predicate: (Int, Boolean) => Type = (_, _) => Type.Bool
  val product: (Int, Boolean) => Type =
    (w, s) => if s then Type.SInt(w + w) else Type.UInt(w + w)

  val binaries: List[BinSpec] = List(
    BinSpec("+",   "+",  (x, y) => x + y,   sameKind),
    BinSpec("-",   "-",  (x, y) => x - y,   sameKind),
    BinSpec("&",   "&",  (x, y) => x & y,   sameKind),
    BinSpec("|",   "|",  (x, y) => x | y,   sameKind),
    BinSpec("^",   "^",  (x, y) => x ^ y,   sameKind),
    BinSpec("===", "==", (x, y) => x === y, predicate),
    BinSpec("=/=", "!=", (x, y) => x =/= y, predicate),
    BinSpec("<",   "<",  (x, y) => x < y,   predicate),
    BinSpec("<=",  "<=", (x, y) => x <= y,  predicate),
    BinSpec(">",   ">",  (x, y) => x > y,   predicate),
    BinSpec(">=",  ">=", (x, y) => x >= y,  predicate),
    BinSpec("*",   "*",  (x, y) => x * y,   product))

  /** The operand width is 8 throughout, so a product is 16 and a literal that
    * takes its sibling's width is 8 — both distinguishable from the operand
    * width in the emitted text. */
  val w = 8

  final class BinCell(signed: Boolean, spec: BinSpec, lk: Kind, rk: Kind) extends Fix:
    private val ty: Type = if signed then SInt(w) else UInt(w)
    val a = In(ty)
    val b = In(ty)
    val m = In(ty)
    val q = Out(spec.result(w, signed))
    q := spec.build(operand(lk, a), operand(rk, b))

    /** A signal, an anonymous width-preserving expression over two signals, or
      * a literal with no width written at it. */
    private def operand(k: Kind, s: DslSignal): Expr = k match
      case Kind.Signal     => s
      case Kind.Expression => s ^ m
      case Kind.Literal    => if signed then 3.S else 3.U

  /** Rules 1 and 3, applied to a binary cell. `None` means the cell must
    * elaborate; `Some(fragment)` means it must fail with a diagnostic
    * containing that fragment. */
  def binExpectation(signed: Boolean, spec: BinSpec, lk: Kind, rk: Kind): Option[String] =
    if lk == Kind.Literal && rk == Kind.Literal then
      Some("no inferrable width")                    // rule 1
    else if signed && spec.scala == "*" && (lk != Kind.Signal || rk != Kind.Signal) then
      Some("requires a named signal")                // rule 3
    else None

  for
    signed <- List(false, true)
    spec   <- binaries
  do
    test(s"${spec.scala} on ${signedness(signed)}($w): every operand-kind pair"):
      for
        lk <- kinds
        rk <- kinds
      do
        val cell = s"${spec.scala} with a ${lk.label} on the left and a ${rk.label} on the right"
        (outcome(new BinCell(signed, spec, lk, rk)), binExpectation(signed, spec, lk, rk)) match
          case (Right(v), None) =>
            assert(v.contains(s" ${spec.verilog} "),
              s"$cell must emit `${spec.verilog}`: $v")
          case (Left(msg), None) =>
            fail(s"$cell must elaborate, but failed:\n$msg")
          case (Right(v), Some(rule)) =>
            fail(s"$cell must be rejected by the rule naming `$rule`, but emitted:\n$v")
          case (Left(msg), Some(rule)) =>
            assert(msg.contains(rule),
              s"$cell must be rejected with a message naming `$rule`, got:\n$msg")

  test("the result width follows the operator, not the operand width"):
    // The product is the only row whose result is wider than its operands, and
    // the predicates the only rows whose result is 1 bit. A cell that agreed
    // with the wrong rule would not have elaborated above; this states it
    // directly so the table itself is checked, not only its consequences.
    class Widths extends Fix:
      val a = In(UInt(w)); val b = In(UInt(w))
      val sum = Out(UInt(w)); val prod = Out(UInt(w + w)); val lt = Out(Bool())
      sum := a + b
      prod := a * b
      lt := a < b
    assert(outcome(new Widths).isRight, outcome(new Widths))

    class ProductTooNarrow extends Fix:
      val a = In(UInt(w)); val b = In(UInt(w))
      val q = Out(UInt(w))
      q := a * b
    val msg = outcome(new ProductTooNarrow).left.getOrElse(fail("a 16-bit product must not fit in 8 bits"))
    assert(msg.contains("16") && msg.contains("8"), s"both widths are named: $msg")

  test("a literal operand takes its sibling's width, including in a product"):
    class Good extends Fix:
      val a = In(UInt(w))
      val sum = Out(UInt(w)); val prod = Out(UInt(w + w))
      sum := a + 3.U
      prod := a * 3.U
    val v = outcome(new Good).getOrElse(fail("literal siblings resolve"))
    assertEquals(v.split("8'd3").length - 1, 2,
      s"both literals take the sibling's 8 bits, not the product's 16: $v")

  // ---- shifts (R21.1) ---------------------------------------------------

  final case class ShiftSpec(scala: String, verilog: String,
                             build: (Expr, Expr) => Expr, signed: Boolean)

  val shifts: List[ShiftSpec] = List(
    ShiftSpec("<<",  "<<",  (x, n) => x << n,  signed = false),
    ShiftSpec(">>",  ">>",  (x, n) => x >> n,  signed = false),
    ShiftSpec("<<",  "<<",  (x, n) => x << n,  signed = true),
    ShiftSpec(">>",  ">>",  (x, n) => x >> n,  signed = true),
    ShiftSpec(">>>", ">>>", (x, n) => x >>> n, signed = true))

  /** The amount is a `UInt(3)` regardless of the value's kind: R21.1 makes it
    * unsigned, of any width, and exempt from width agreement. */
  final class ShiftCell(spec: ShiftSpec, vk: Kind, ak: Kind) extends Fix:
    private val ty: Type = if spec.signed then SInt(w) else UInt(w)
    val a = In(ty)
    val m = In(ty)
    val n = In(UInt(3))
    val k = In(UInt(3))
    val q = Out(ty)
    q := spec.build(value(vk), amount(ak))

    private def value(kind: Kind): Expr = kind match
      case Kind.Signal     => a
      case Kind.Expression => a ^ m
      case Kind.Literal    => if spec.signed then 3.S else 3.U

    private def amount(kind: Kind): Expr = kind match
      case Kind.Signal     => n
      case Kind.Expression => n ^ k
      case Kind.Literal    => 3.U

  for spec <- shifts do
    test(s"${spec.scala} on ${signedness(spec.signed)}($w): every value/amount kind pair"):
      for
        vk <- kinds
        ak <- kinds
      do
        val cell = s"${spec.scala} with a ${vk.label} value and a ${ak.label} amount"
        // Rule 2: the amount gives no width back, so a literal value has no
        // source of one. Every other pair elaborates — including a literal
        // amount, which takes its own minimal width.
        val expectation = if vk == Kind.Literal then Some("no inferrable width") else None
        (outcome(new ShiftCell(spec, vk, ak)), expectation) match
          case (Right(v), None) =>
            assert(v.contains(s" ${spec.verilog} "),
              s"$cell must emit `${spec.verilog}`: $v")
          case (Left(msg), None) =>
            fail(s"$cell must elaborate, but failed:\n$msg")
          case (Right(v), Some(rule)) =>
            fail(s"$cell must be rejected by the rule naming `$rule`, but emitted:\n$v")
          case (Left(msg), Some(rule)) =>
            assert(msg.contains(rule),
              s"$cell must be rejected with a message naming `$rule`, got:\n$msg")

  test("a shift's result keeps the value's width and the amount's stays its own"):
    class Good extends Fix:
      val a = In(UInt(w)); val n = In(UInt(3))
      val q = Out(UInt(w))
      q := a >> n
    val v = outcome(new Good).getOrElse(fail("a shift elaborates"))
    assert(v.contains("[7:0] q") || v.contains("reg [7:0] q"),
      s"the result is the value's 8 bits, not the amount's 3: $v")

  // ---- unary operators, reductions, and conversions (R21, R21.4) --------

  final case class UnarySpec(scala: String, verilog: String,
                             build: Expr => Expr,
                             operand: Boolean => Type,
                             result: Boolean => Type,
                             expressionOperandRule: Option[String] = None)

  /** Each row's operand type and result type as a function of signedness, so
    * the same table runs on `UInt` and on `SInt` where both are admitted. */
  val scalar: Boolean => Type = s => if s then Type.SInt(w) else Type.UInt(w)
  val wider: Boolean => Type = s => if s then Type.SInt(w + w) else Type.UInt(w + w)
  val boolean: Boolean => Type = _ => Type.Bool

  val unaries: List[UnarySpec] = List(
    UnarySpec("~",       "~",   x => ~x,           scalar, scalar),
    UnarySpec("orR",     "(|",  x => x.orR,        scalar, boolean),
    UnarySpec("andR",    "(&",  x => x.andR,       scalar, boolean),
    UnarySpec("xorR",    "(^",  x => x.xorR,       scalar, boolean),
    // R21.4 — widening a UInt fills with zeros and needs no bit-select;
    // widening an SInt replicates its sign bit, which does (rule 3).
    UnarySpec("resize", "{{8{", x => x.resize(w + w), scalar, wider,
      expressionOperandRule = Some("requires a named signal")))

  final class UnaryCell(signed: Boolean, spec: UnarySpec, k: Kind) extends Fix:
    private val ty: Type = spec.operand(signed)
    val a = In(ty)
    val m = In(ty)
    val q = Out(spec.result(signed))
    q := spec.build(operand(k))

    private def operand(kind: Kind): Expr = kind match
      case Kind.Signal     => a
      case Kind.Expression => a ^ m
      case Kind.Literal    => if signed then 3.S else 3.U

  for
    signed <- List(false, true)
    spec   <- unaries
  do
    test(s"${spec.scala} on ${signedness(signed)}($w): signal, expression, literal"):
      for k <- kinds do
        val cell = s"${spec.scala} of a ${k.label}"
        val expectation = k match
          // Rule 1 — a unary operator has no sibling at all, so a literal
          // operand never has a width, on any row.
          case Kind.Literal    => Some("no inferrable width")
          case Kind.Expression => if signed then spec.expressionOperandRule else None
          case Kind.Signal     => None
        (outcome(new UnaryCell(signed, spec, k)), expectation) match
          case (Right(v), None) =>
            assert(v.contains(spec.verilog), s"$cell must emit `${spec.verilog}`: $v")
          case (Left(msg), None) =>
            fail(s"$cell must elaborate, but failed:\n$msg")
          case (Right(v), Some(rule)) =>
            fail(s"$cell must be rejected by the rule naming `$rule`, but emitted:\n$v")
          case (Left(msg), Some(rule)) =>
            assert(msg.contains(rule),
              s"$cell must be rejected with a message naming `$rule`, got:\n$msg")

  test("`!` admits a Bool signal and a Bool expression, and nothing else"):
    class Good extends Fix:
      val f = In(Bool()); val g = In(Bool())
      val one = Out(Bool()); val two = Out(Bool())
      one := !f
      two := !(f ^ g)
    val v = outcome(new Good).getOrElse(fail("`!` takes a signal or an expression"))
    assert(v.contains("(!f)") && v.contains("(!(f ^ g))"), v)

  test("the three casts apply to a signal and to an anonymous expression"):
    class Good extends Fix:
      val a = In(UInt(w)); val b = In(UInt(w))
      val u = Out(UInt(w)); val s = Out(SInt(w)); val t = Out(Bits(w))
      val ue = Out(UInt(w)); val se = Out(SInt(w)); val te = Out(Bits(w))
      u := a.asUInt
      s := a.asSInt
      t := a.asBits
      ue := (a ^ b).asUInt
      se := (a ^ b).asSInt
      te := (a ^ b).asBits
    val v = outcome(new Good).getOrElse(fail("casts compose over anonymous trees"))
    // R21.4 — a cast emits its operand unchanged, so nothing appears between
    // the assignment and the operand in any of the six drivers.
    assertEquals(v.split("\\(a \\^ b\\)").length - 1, 3, s"three anonymous casts, each transparent: $v")

  test("a cast of an unwidthed literal names the width, not the operand"):
    class Bad extends Fix:
      val q = Out(UInt(w))
      q := 3.U.asUInt
    val msg = outcome(new Bad).left.getOrElse(fail("an unwidthed literal has no width to preserve"))
    assert(msg.contains("width"), msg)
    assert(msg.contains(".asUInt"), s"the message names the operation: $msg")

  // ---- Cat and Fill parts (R21.5) ---------------------------------------

  test("a Cat part may be a signal, an expression, or an explicitly-widthed literal"):
    class Good extends Fix:
      val a = In(UInt(4)); val b = In(UInt(4))
      val q = Out(UInt(9))
      q := Cat(a, a ^ b, 1.U(1))
    val v = outcome(new Good).getOrElse(fail("all three part kinds are admissible"))
    assert(v.contains("{a, (a ^ b), 1'b1}"), s"one concatenation, parts in order: $v")

  test("an unwidthed literal part is rejected, on either end"):
    class First extends Fix:
      val a = In(UInt(4))
      val q = Out(UInt(8))
      q := Cat(1.U, a)
    class Last extends Fix:
      val a = In(UInt(4))
      val q = Out(UInt(8))
      q := Cat(a, 1.U)
    List(outcome(new First), outcome(new Last)).foreach { result =>
      val msg = result.left.getOrElse(fail("a part with no width changes the width of the whole"))
      assert(msg.contains("width"), msg)
    }

  test("a Fill repeats a signal or an expression; a literal has no width"):
    class Good extends Fix:
      val a = In(UInt(4)); val b = In(UInt(4))
      val one = Out(UInt(12)); val two = Out(UInt(12))
      one := Fill(3, a)
      two := Fill(3, a ^ b)
    val v = outcome(new Good).getOrElse(fail("Fill takes a signal or an expression"))
    assert(v.contains("{3{a}}") && v.contains("{3{(a ^ b)}}"), v)

    class Bad extends Fix:
      val q = Out(UInt(12))
      q := Fill(3, 1.U)
    assert(outcome(new Bad).isLeft, "an unwidthed literal has no width to repeat")

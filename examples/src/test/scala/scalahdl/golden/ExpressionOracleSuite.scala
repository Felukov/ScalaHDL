package scalahdl.golden

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.sys.process.*

import scalahdl.clock.ClockDomain
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** R21.1 / R21.2 / R21.3 / R21.6 — the simulation oracle.
  *
  * Three of the milestone's exit criteria cannot be met by reading the emitted
  * text: a logical shift that should have been arithmetic, a comparison that
  * evaluated unsigned, and a product whose operands were sized by their
  * context all emit legal Verilog that lints clean and computes the wrong
  * number. Only running it says otherwise.
  *
  * So `ExprOracle` is elaborated, emitted, built by Verilator, and driven with
  * vectors whose expected values are computed here in Scala with `BigInt`,
  * from the specification's arithmetic rather than from a second model of the
  * emitter. The testbench applies inputs and reports disagreement; it decides
  * nothing.
  *
  * What the vectors are chosen to prove:
  *
  *   - R21.1 — `>>` is zero fill even on `SInt`, and differs from `>>>` for a
  *     negative operand; boundary amounts 0, `w-1`, `w`, and amounts whose
  *     width exceeds `log2(w)` all behave.
  *   - R21.2 — the `SLT`/`SLTU` pair disagree on the same bit patterns, each
  *     correctly, and all four orderings are exercised at equality and at both
  *     adjacent values.
  *   - R21.3 — signed extrema (`0x8000_0000 * 0x8000_0000`, `-1 * -1`) and
  *     `MULHSU` for a negative `rs1` match the `BigInt` oracle.
  *   - R21.6 — the context-independence invariant: five expressions, each
  *     bound to a `val` and each inlined into a wider context, produce the
  *     same simulated value. */
final class ExpressionOracleSuite extends munit.FunSuite:

  // ---- the oracle: two's complement arithmetic over BigInt ---------------

  private val mask32 = (BigInt(1) << 32) - 1
  private val mask64 = (BigInt(1) << 64) - 1

  /** The unsigned reading of a 32-bit pattern. */
  private def u32(x: BigInt): BigInt = x & mask32

  /** The signed reading of the same pattern. */
  private def s32(x: BigInt): BigInt =
    val v = u32(x)
    if v.testBit(31) then v - (BigInt(1) << 32) else v

  private def bit(b: Boolean): BigInt = if b then 1 else 0

  final case class Vec(a: BigInt, b: BigInt, amt: Int):
    override def toString = f"a=0x${a}%08X b=0x${b}%08X amt=$amt"

  /** Every output port's expected value, named as the testbench names it.
    * `BigInt` shifts are arithmetic on negative values and unbounded on the
    * left, so each result is masked back to its port width — which is exactly
    * what "bits shifted out are dropped" (R21.1) and "the product is `w1+w2`
    * wide" (R21.3) mean. */
  private def oracle(v: Vec): List[(String, BigInt)] =
    val (ua, ub) = (u32(v.a), u32(v.b))
    val (sa, sb) = (s32(v.a), s32(v.b))
    val n = v.amt

    // R21.1 — `>>` is logical on the two's-complement pattern whatever the
    // operand's type; only `>>>` propagates the sign.
    val logicalRight = ua >> n
    val arithmeticRight = u32(sa >> n)
    val leftShift = u32(ua << n)

    // R21.6 — the same expressions the DUT computes twice, bound and inlined.
    val sum = u32(ua + ub)
    val cat = ((ua & 0xffff) << 16) | (ub & 0xffff)
    val sraSigned = s32(arithmeticRight)

    List(
      // R21.1
      "sll" -> leftShift,
      "srl" -> logicalRight,
      "sra" -> arithmeticRight,
      "srlS" -> logicalRight,          // an SInt shifted with `>>` fills zeros

      // R21.2
      "ltu" -> bit(ua < ub),
      "leu" -> bit(ua <= ub),
      "gtu" -> bit(ua > ub),
      "geu" -> bit(ua >= ub),
      "lts" -> bit(sa < sb),
      "les" -> bit(sa <= sb),
      "gts" -> bit(sa > sb),
      "ges" -> bit(sa >= sb),

      // R21.3 — the full product, and the three RISC-V high halves
      "mulu" -> ((ua * ub) & mask64),
      "muls" -> ((sa * sb) & mask64),
      "mulh" -> (((sa * sb) >> 32) & mask32),
      "mulhu" -> (((ua * ub) >> 32) & mask32),
      "mulhsu" -> (((sa * ub) >> 32) & mask32),

      // R21.6 — each expected value is shared by the bound and the inlined
      // spelling, so agreeing with the oracle is agreeing with each other.
      "p1Bound" -> ((leftShift * ub) & mask64),
      "p1Inline" -> ((leftShift * ub) & mask64),
      "p2Bound" -> ((sum * ub) & mask64),
      "p2Inline" -> ((sum * ub) & mask64),
      "p3Bound" -> bit(sraSigned < sb),
      "p3Inline" -> bit(sraSigned < sb),
      "p4Bound" -> u32(sraSigned + sb),
      "p4Inline" -> u32(sraSigned + sb),
      "p5Bound" -> ((cat * ub) & mask64),
      "p5Inline" -> ((cat * ub) & mask64),
      "p6Bound" -> ((((sa * sb) & mask64) + ((ua * ub) & mask64)) & mask64),
      "p6Inline" -> ((((sa * sb) & mask64) + ((ua * ub) & mask64)) & mask64),
      "p7Bound" -> ((arithmeticRight * ub) & mask64),
      "p7Inline" -> ((arithmeticRight * ub) & mask64),
      "p8Bound" -> (((sa & mask64) + ((ua * ub) & mask64)) & mask64),
      "p8Inline" -> (((sa & mask64) + ((ua * ub) & mask64)) & mask64))

  // ---- the vectors -------------------------------------------------------

  /** Adjacent triples around every boundary of the 32-bit signed and unsigned
    * ranges, so the full cross product contains each ordering at equality and
    * at both adjacent values, and contains `0x8000_0000` against `1`. */
  private val patterns: List[BigInt] = List(
    "00000000", "00000001", "00000002",
    "7FFFFFFE", "7FFFFFFF",
    "80000000", "80000001",
    "FFFFFFFD", "FFFFFFFE", "FFFFFFFF",
    "DEADBEEF", "12345678").map(BigInt(_, 16))

  /** R21.1's boundary amounts for `w = 32`: nothing, one, an ordinary amount,
    * `w - 1`, `w`, past `w`, and an amount that needs all 8 bits of a port
    * three times wider than `log2(32)`. */
  private val amounts: List[Int] = List(0, 1, 4, 31, 32, 33, 255)

  private val vectors: List[Vec] =
    for
      a <- patterns
      b <- patterns
      amt <- amounts
    yield Vec(a, b, amt)

  // ---- the tests ---------------------------------------------------------

  test("the vector set covers each ordering at equality and at both neighbours"):
    // The coverage claim R21.2 makes is itself checked, so a later edit to
    // `patterns` cannot quietly drop it.
    val present = vectors.map(v => (v.a, v.b)).toSet
    List("00000001", "7FFFFFFF", "80000000", "FFFFFFFE").map(BigInt(_, 16)).foreach { v =>
      val hex = v.toString(16)
      assert(present.contains((v, v)), s"equality at 0x$hex")
      assert(present.contains((v, v + 1)), s"the value above 0x$hex")
      assert(present.contains((v, v - 1)), s"the value below 0x$hex")
    }
    assert(present.contains((BigInt("80000000", 16), BigInt(1))),
      "the SLT/SLTU directed vector: 0x8000_0000 against 1")

  test("SLT and SLTU disagree on the same bits, and the oracle says both are right"):
    val v = Vec(BigInt("80000000", 16), BigInt(1), 0)
    val out = oracle(v).toMap
    assertEquals(out("ltu"), BigInt(0), "unsigned: 0x8000_0000 is the larger")
    assertEquals(out("lts"), BigInt(1), "signed: 0x8000_0000 is the most negative")

  test("the vectors include a negative operand where `>>` and `>>>` must differ"):
    val differing = vectors.filter { v =>
      val out = oracle(v).toMap
      out("sra") != out("srlS")
    }
    assert(differing.nonEmpty,
      "a logical and an arithmetic right shift must be distinguishable")
    // The simulation below asserts both against the oracle, so proving them
    // different here proves the hardware different too.
    assert(differing.exists(v => v.amt >= 32),
      "including past the operand width, where the two disagree the most")

  test("the signed extrema are in the vector set"):
    val present = vectors.map(v => (v.a, v.b)).toSet
    val min = BigInt("80000000", 16)
    val ones = BigInt("FFFFFFFF", 16)
    assert(present.contains((min, min)), "0x8000_0000 * 0x8000_0000")
    assert(present.contains((ones, ones)), "-1 * -1")
    val negOne = oracle(Vec(ones, ones, 0)).toMap
    val mostNegative = oracle(Vec(min, min, 0)).toMap
    assertEquals(negOne("muls"), BigInt(1),
      "-1 * -1 is 1 in the full 64-bit product")
    assertEquals(mostNegative("muls"), BigInt(1) << 62,
      "0x8000_0000 squared is 2^62")

  test("MULHSU for a negative rs1 is the RISC-V-defined result"):
    // rs1 = -1, rs2 = 0xFFFF_FFFF read unsigned: the product is
    // -(2^32 - 1), whose high half is 0xFFFF_FFFF.
    val out = oracle(Vec(BigInt("FFFFFFFF", 16), BigInt("FFFFFFFF", 16), 0)).toMap
    assertEquals(out("mulhsu"), BigInt("FFFFFFFF", 16))
    assertEquals(out("mulhu"), BigInt("FFFFFFFE", 16), "unsigned x unsigned differs")
    assertEquals(out("mulh"), BigInt(0), "signed x signed differs again")

  test("emitted Verilog agrees with the BigInt oracle on every vector"):
    assume(Seq("sh", "-c", "command -v verilator >/dev/null 2>&1").! == 0,
      "Verilator not on PATH; skipping R21's simulation oracle")

    val temp = Files.createTempDirectory("scalahdl-expr-oracle-")
    val design = (new ExprOracle).elaborateDesign()
    val emitted = new Emission("0.1.2").emit(design)
    assertEquals(emitted.files.map(_._1), List("ExprOracle.v"))
    val verilog = temp.resolve("ExprOracle.v")
    Files.writeString(verilog, emitted.files.head._2, StandardCharsets.UTF_8)

    val names = oracle(vectors.head).map(_._1)
    val rows = vectors.map { v =>
      val expected = oracle(v)
      assertEquals(expected.map(_._1), names, "every vector names the same outputs")
      (List(u32(v.a), u32(v.b), BigInt(v.amt)) ++ expected.map(_._2)).mkString(" ")
    }
    val vectorFile = temp.resolve("vectors.txt")
    Files.writeString(vectorFile, (names.mkString(" ") :: rows).mkString("\n") + "\n",
      StandardCharsets.UTF_8)

    val tbResource = Option(getClass.getResourceAsStream("/scalahdl/verilator/expr_oracle_tb.cpp"))
      .getOrElse(fail("missing expr_oracle_tb.cpp resource"))
    val tb = temp.resolve("expr_oracle_tb.cpp")
    try Files.copy(tbResource, tb) finally tbResource.close()

    val build = Seq(
      "verilator", "--cc", "--exe", "--build", "--top-module", "ExprOracle",
      "-Wno-fatal", verilog.toString, tb.toString)
    val buildOutput = new StringBuilder
    val buildCode = Process(build, temp.toFile).!(ProcessLogger(
      line => buildOutput.append(line).append('\n'),
      line => buildOutput.append(line).append('\n')))
    assertEquals(buildCode, 0, buildOutput.toString)

    // The mismatch report is the diagnostic that matters here, so it is
    // captured rather than thrown away with the exit code.
    val simOutput = new StringBuilder
    val simCode = Process(
      Seq(temp.resolve("obj_dir/VExprOracle").toString, vectorFile.toString), temp.toFile)
      .!(ProcessLogger(
        line => simOutput.append(line).append('\n'),
        line => simOutput.append(line).append('\n')))
    assertEquals(simCode, 0, simOutput.toString)
    assert(simOutput.toString.contains(s"PASS: ${vectors.size} vectors"), simOutput.toString)

/** The design under oracle: purely combinational, every port ≤ 64 bits so the
  * testbench can read it as one integer, and every R21 operation whose meaning
  * a text assertion cannot check.
  *
  * The five `p*Bound`/`p*Inline` pairs are R21.6's invariant written as
  * hardware: the same expression bound to a `val` and inlined into a wider
  * context. Each pair is a place where Verilog's context-determined sizing
  * would show up as a difference — a left shift whose dropped bits came back,
  * a sum that kept its carry, an arithmetic shift widened before it shifted. */
class ExprOracle extends Module:
  given ClockDomain = ClockDomain.noClock

  val a = In(UInt(32))
  val b = In(UInt(32))
  val amt = In(UInt(8))

  /** The same bits, read as signed. R6 keeps the two readings apart, and
    * R21.4's cast is where crossing between them is written. */
  val sa = Wire(SInt(32)); sa := a.asSInt
  val sb = Wire(SInt(32)); sb := b.asSInt

  // ---- R21.1: shifts ----------------------------------------------------

  val sll = Out(UInt(32)); sll := a << amt
  val srl = Out(UInt(32)); srl := a >> amt
  val sra = Out(SInt(32)); sra := sa >>> amt
  val srlS = Out(SInt(32)); srlS := sa >> amt

  // ---- R21.2: ordering, unsigned and signed -----------------------------

  val ltu = Out(Bool()); ltu := a < b
  val leu = Out(Bool()); leu := a <= b
  val gtu = Out(Bool()); gtu := a > b
  val geu = Out(Bool()); geu := a >= b
  val lts = Out(Bool()); lts := sa < sb
  val les = Out(Bool()); les := sa <= sb
  val gts = Out(Bool()); gts := sa > sb
  val ges = Out(Bool()); ges := sa >= sb

  // ---- R21.3: the full product and the RISC-V high halves ---------------

  val pu = Wire(UInt(64)); pu := a * b
  val ps = Wire(SInt(64)); ps := sa * sb
  val mulu = Out(UInt(64)); mulu := pu
  val muls = Out(SInt(64)); muls := ps
  val mulhu = Out(UInt(32)); mulhu := pu(63, 32)
  val mulh = Out(UInt(32)); mulh := ps(63, 32)

  // `MULHSU` — signed rs1 by unsigned rs2. Both operands are widened to a
  // common *signed* width first: rs1 sign-extends, rs2 zero-extends into a
  // 33-bit value that is therefore never negative. The 66-bit product's
  // bits 63:32 are the defined result.
  val sa33 = Wire(SInt(33)); sa33 := sa.resize(33)
  val ub33 = Wire(SInt(33)); ub33 := b.resize(33).asSInt
  val psu = Wire(SInt(66)); psu := sa33 * ub33
  val mulhsu = Out(UInt(32)); mulhsu := psu(63, 32)

  // ---- R21.6: the context-independence invariant ------------------------

  // A left shift drops the bits it pushes past 32; a 64-bit context must not
  // give them back (R21.1's non-goal, R21.6's invariant).
  val shl = Wire(UInt(32)); shl := a << amt
  val p1Bound = Out(UInt(64)); p1Bound := shl * b
  val p1Inline = Out(UInt(64)); p1Inline := (a << amt) * b

  // A sum drops its carry (R6.2); a wider context must not keep it.
  val sum = Wire(UInt(32)); sum := a + b
  val p2Bound = Out(UInt(64)); p2Bound := sum * b
  val p2Inline = Out(UInt(64)); p2Inline := (a + b) * b

  // An arithmetic shift inside a signed comparison, and inside a signed sum:
  // the shift must happen at 32 bits, not at whatever width the surrounding
  // operation settles on.
  val shra = Wire(SInt(32)); shra := sa >>> amt
  val p3Bound = Out(Bool()); p3Bound := shra < sb
  val p3Inline = Out(Bool()); p3Inline := (sa >>> amt) < sb
  val p4Bound = Out(SInt(32)); p4Bound := shra + sb
  val p4Inline = Out(SInt(32)); p4Inline := (sa >>> amt) + sb

  // A concatenation keeps its own summed width as a multiply operand (R21.5).
  val cat = Wire(UInt(32)); cat := Cat(a(15, 0), b(15, 0))
  val p5Bound = Out(UInt(64)); p5Bound := cat * b
  val p5Inline = Out(UInt(64)); p5Inline := Cat(a(15, 0), b(15, 0)) * b

  // The remaining three put a *signed* value into an unsigned context, which
  // is where Verilog's "any unsigned operand makes the whole expression
  // unsigned" rule reaches down into the operands: a signed product, an
  // arithmetic shift, and a sign-extending resize, each crossing R6's
  // signedness barrier at the cast and then meeting an unsigned sibling.
  val psU = Wire(UInt(64)); psU := ps.asUInt
  val p6Bound = Out(UInt(64)); p6Bound := psU + pu
  val p6Inline = Out(UInt(64)); p6Inline := (sa * sb).asUInt + pu

  val shraU = Wire(UInt(32)); shraU := shra.asUInt
  val p7Bound = Out(UInt(64)); p7Bound := shraU * b
  val p7Inline = Out(UInt(64)); p7Inline := (sa >>> amt).asUInt * b

  val saWide = Wire(SInt(64)); saWide := sa.resize(64)
  val p8Bound = Out(UInt(64)); p8Bound := saWide.asUInt + pu
  val p8Inline = Out(UInt(64)); p8Inline := sa.resize(64).asUInt + pu

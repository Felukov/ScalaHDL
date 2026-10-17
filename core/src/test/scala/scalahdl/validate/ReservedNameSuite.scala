package scalahdl.validate

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, moduleName}
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Diagnostic, Code}

/** R1.1 — a name is not a keyword. Every kind of name the emitted Verilog
  * carries, each named with a SystemVerilog keyword, is `NAME_RESERVED` at the
  * declaration; the near misses are names. */
class ReservedNameSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  private def reserved(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed](body.elaborateDesign()).diagnostics.filter(_.code == Code.NAME_RESERVED)

  private def one(body: => Module, summary: String): Unit =
    val found = reserved(body)
    assertEquals(found.length, 1, found.map(_.render))
    assertEquals(found.head.summary, summary)

  test("a register named `byte`"):
    class Bad extends Fix:
      val d = In(UInt(8))
      val byte = Reg(UInt(8), init = 0.U(8))
      val q = Out(UInt(8))
      byte := d
      q := byte
    one(new Bad, "`byte` is a SystemVerilog keyword and cannot name a signal")

  test("a port named `input`"):
    class Bad extends Fix:
      val input = In(Bool())
      val q = Out(Bool())
      q := input
    one(new Bad, "`input` is a SystemVerilog keyword and cannot name a port")

  test("a bundle whose leaf is a keyword though neither of its parts is"):
    final case class Guard() extends Bundle:
      val on = Bool()
    class Bad extends Fix:
      val accept = In(Guard())
      val q = Out(Bool())
      q := accept.on
    one(new Bad, "`accept_on` is a SystemVerilog keyword and cannot name a leaf of the port `accept`")

  test("an instance named `table`"):
    class Child extends Fix:
      val a = In(Bool())
      val y = Out(Bool())
      y := a
    class Bad extends Fix:
      val a = In(Bool())
      val y = Out(Bool())
      val table = inst(new Child)
      table.clk := clk
      table.rst := rst
      table.a := a
      y := table.y
    one(new Bad, "`table` is a SystemVerilog keyword and cannot name an instance")

  test("a memory named `wire`"):
    class Bad extends Fix:
      val addr = In(UInt(4))
      val q = Out(UInt(8))
      val wire = LutRam(16, UInt(8))
      val read = wire.readAsync(addr, Undefined)
      q := read
    one(new Bad, "`wire` is a SystemVerilog keyword and cannot name a memory")

  test("a module named `reg`"):
    class Bad extends Module(moduleName("reg")):
      val a = In(Bool())
      val y = Out(Bool())
      y := a
    one(new Bad, "`reg` is a SystemVerilog keyword and cannot name a module")

  test("the near misses are names: another spelling, another case, a C++ word"):
    class Good extends Fix:
      val bytes = In(UInt(8))
      val Byte  = Out(UInt(8))
      val abort = Out(Bool())
      Byte  := bytes
      abort := bytes(0)
    (new Good).elaborateDesign()

  test("the table is IEEE 1800-2017 Annex B, all 248 words, every revision's among them"):
    assertEquals(ReservedWords.systemVerilog2017.size, 248)
    // 1364-1995, -2001, -2005; 1800-2005, -2009, -2012.
    for word <- List("wire", "generate", "uwire", "byte", "accept_on", "global", "implements", "soft") do
      assert(ReservedWords.reserved(word), word)
    for name <- List("Byte", "bytes", "abort", "std") do
      assert(!ReservedWords.reserved(name), name)
end ReservedNameSuite

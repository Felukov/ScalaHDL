package scalahdl.hierarchy

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveLow
import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, param}
import scalahdl.emit.Emission

/** R16 — an array of instances. `instances(n)(...)` declares a count that is a
  * *value*, naming the children `<val>_0` … `<val>_{n-1}`, which is the same
  * numeric segment R07.1 appends when it flattens a `Vec`. Everything else
  * about them is R23's ordinary instance: connections, parent-side nets, and
  * R16 deduplication. */
final class InstanceArraySuite extends munit.FunSuite:

  class Cell(width: Int) extends Module(param("w", width)):
    val in = In(UInt(width))
    val out = Out(UInt(width))
    given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
    out := in + 1.U(width)

  class Row(n: Int) extends Module(param("n", n)):
    val clk = In(Clock())
    val resetn = In(Reset(ActiveLow))
    val x = In(UInt(8))
    val y = Out(UInt(8))
    given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)

    val cell = instances(n)(_ => new Cell(8))
    // A chain: each cell takes the previous one's output net.
    for i <- 0 until n do
      cell(i).in := (if i == 0 then x.ref else cell(i - 1).out.ref)
    y := cell(n - 1).out

  private def emit(top: Module): String =
    new Emission().emit(top.elaborateDesign()).files.last._2

  test("the array names its instances `<val>_<i>`, in index order"):
    val design = (new Row(3)).elaborateDesign()
    assertEquals(design.top.instances.map(_.name), List("cell_0", "cell_1", "cell_2"))

  test("each element's parent-side output net follows its own instance name"):
    val verilog = emit(new Row(3))
    assert(verilog.contains("wire [7:0] cell_0_out;"), verilog)
    assert(verilog.contains("wire [7:0] cell_1_out;"), verilog)
    assert(verilog.contains("wire [7:0] cell_2_out;"), verilog)
    assert(verilog.contains(".in(cell_0_out)"), verilog)
    assert(verilog.contains("y = cell_2_out;"), verilog)

  test("identical elements deduplicate to one emitted module (R16)"):
    val emitted = new Emission().emit((new Row(3)).elaborateDesign())
    assertEquals(emitted.files.map(_._1), List("Cell_w8.v", "Row_n3.v"))

  test("the factory sees the index, so an array may vary its parameters"):
    class Ladder extends Module:
      val clk = In(Clock())
      val resetn = In(Reset(ActiveLow))
      val y = Out(UInt(10))
      given defaultDomain: ClockDomain = ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)
      val step = instances(2)(i => new Cell(8 + i * 2))
      step(0).in := 0.U(8)
      step(1).in := 0.U(10)
      y := step(1).out

    val emitted = new Emission().emit((new Ladder).elaborateDesign())
    assertEquals(emitted.files.map(_._1), List("Cell_w8.v", "Cell_w10.v", "Ladder.v"))
    val top = emitted.files.last._2
    assert(top.contains("Cell_w8 step_0("), top)
    assert(top.contains("Cell_w10 step_1("), top)

  test("an empty array is rejected at the declaration"):
    class None_ extends Module:
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val cell = instances(0)(_ => new Cell(8))

    val error = intercept[IllegalArgumentException](new None_)
    assert(error.getMessage.nn.contains("instances(n) requires n > 0"), error.getMessage)

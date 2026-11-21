package scalahdl.golden

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.sys.process.*

import scalahdl.elab.Module
import scalahdl.emit.Emission
import scalahdl.examples.{FetchFsm, FetchMachine, FetchMachineReference}

/** R25 — the same fetch controller written twice, as an R22 `switch` and as a
  * declared machine.
  *
  * This is the milestone's load-bearing claim end to end: a machine is a
  * surface over the switch, so the two designs must emit the same module body
  * byte for byte. Anything that made `Fsm` more than a surface — a synthesised
  * signal, a reordered arm, a state register that is not the `val` — shows up
  * here as a diff, in the one place where both spellings of a real design
  * exist side by side.
  *
  * The report is checked here rather than in `core` for the same reason: this
  * is the first design whose diagram someone would actually read. */
final class FsmMachineGoldenSuite extends munit.FunSuite:
  private val version = "0.1.2"

  private def emitted(m: => Module) =
    new Emission(version).emit(m.elaborateDesign())

  /** The module body with the R20.2 header and the module's own name removed:
    * the two designs are different modules and say so, and everything below
    * that line is the hardware. */
  private def body(verilog: String, name: String): String =
    verilog.linesIterator.filterNot(_.trim.startsWith("//"))
      .map(_.replace(name, "Fetch")).mkString("\n")

  test("a declared machine emits the same Verilog as the switch it stands for"):
    // `FetchMachineReference` is the same design in the same declaration order,
    // written as a `switch`. The order matters and is R2's doing, not R25's: a
    // machine declares its register where it is written, so the signals its
    // body assigns are declared above it.
    val switchForm = body(emitted(new FetchMachineReference).files.head._2, "FetchMachineReference")
    val machineForm = body(emitted(new FetchMachine).files.head._2, "FetchMachine")
    assertEquals(machineForm, switchForm)

  test("the machine and the switch it stands for emit the same `case`"):
    // `FetchFsm` declares its register first and writes the switch later, which
    // is the one thing a machine cannot do, so its signals and driver blocks
    // come out in a different order (R2). What `Fsm` is a surface *over* — the
    // partition — is therefore compared on its own, across the two emissions.
    //
    // The comparison is between two emissions of the same emitter, so it states
    // that the two spellings agree and says nothing about what the text must
    // be: a change to the emitted format moves both sides and this still
    // passes. What the bytes must be is the `core` corpus's business.
    def caseBlocks(verilog: String): List[String] =
      def loop(from: Int, found: List[String]): List[String] =
        val start = verilog.indexOf("case (", from)
        if start < 0 then found.sorted
        else
          val end = verilog.indexOf("endcase", start) + "endcase".length
          loop(end, verilog.substring(start, end) :: found)
      loop(0, Nil)
    val machineBlocks = caseBlocks(emitted(new FetchMachine).files.head._2)
    val switchBlocks = caseBlocks(emitted(new FetchFsm).files.head._2)
    // Non-empty on both sides, so a formatter that stopped emitting a `case`
    // could not pass this by having nothing to disagree about.
    assert(machineBlocks.nonEmpty, "the machine must emit at least one case")
    assert(switchBlocks.nonEmpty, "the switch form must emit at least one case")
    assertEquals(machineBlocks, switchBlocks)

  test("the machine's Verilog passes verilator --lint-only -Wall"):
    assume(Seq("sh", "-c", "command -v verilator >/dev/null 2>&1").! == 0,
      "Verilator not on PATH; skipping R25's lint criterion")
    val dir = Files.createTempDirectory("scalahdl-fsm-lint")
    try
      val file = dir.resolve("FetchMachine.v")
      Files.writeString(file, emitted(new FetchMachine).files.head._2, StandardCharsets.UTF_8)
      val output = new StringBuilder
      val code = Process(Seq("verilator", "--lint-only", "-Wall", file.toString)).!(
        ProcessLogger(line => output.append(line).append('\n'),
                      line => output.append(line).append('\n')))
      assertEquals(code, 0, s"verilator rejected the machine:\n$output")
    finally
      val _ = Seq("rm", "-rf", dir.toString).!

  test("the machine ships a report and the switch does not"):
    val machineReports = emitted(new FetchMachine).reports.map(_._1)
    assert(machineReports.contains("FetchMachine.fsm.md"), machineReports.toString)
    val switchReports = emitted(new FetchFsm).reports.map(_._1)
    assert(!switchReports.exists(_.endsWith(".fsm.md")),
      s"a hand-written switch is not a declared machine: $switchReports")

  test("the report draws every state and edge of the fetch controller"):
    val report = emitted(new FetchMachine).reports
      .find(_._1 == "FetchMachine.fsm.md").map(_._2).getOrElse(fail("no report"))
    assert(report.contains("## fetchState — FetchState, 2 bits, initial Reset"), report)
    assert(report.contains("[*] --> Reset"), report)
    assert(report.contains("Reset --> Request"), report)
    assert(report.contains("Request --> Wait"), report)
    assert(report.contains("Request --> Decode : mem_ready"), report)
    assert(report.contains("Wait --> Decode : mem_ready"), report)
    assert(report.contains("Wait --> Wait"), report)
    assert(report.contains("Decode --> Request"), report)
    // Every edge cites the `goto` that produced it, which is what makes the
    // diagram navigable back into the source.
    assert(report.linesIterator.count(_.contains("FetchFsm.scala:")) >= 6, report)

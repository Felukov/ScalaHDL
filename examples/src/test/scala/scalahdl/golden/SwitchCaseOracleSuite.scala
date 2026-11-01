package scalahdl.golden

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.sys.process.*

import scalahdl.emit.Emission
import scalahdl.examples.{FetchFsm, FetchFsmReference}

/** R22 — the fetch FSM's transitions, simulated.
  *
  * The milestone's first exit criterion is that the FSM "emits a native
  * Verilog case whose simulated transitions match the original logic". The
  * original logic is `FetchFsmReference`: the same controller as four
  * equality-tested `when` blocks, which is what the design looked like before
  * M14. Both are emitted, both are built into one Verilator model through a
  * hand-written wrapper, and the testbench requires their outputs to agree on
  * every cycle under one stimulus stream.
  *
  * Equivalence is the assertion because it is the one that cannot be satisfied
  * by a testbench with a wrong idea of the FSM: no expected trace is written
  * down anywhere, so the only way to pass is for the two implementations to
  * behave identically. The testbench separately refuses a vacuous pass — every
  * state must be entered and both handshake shapes must occur. */
final class SwitchCaseOracleSuite extends munit.FunSuite:

  private def copyResource(name: String, target: Path): Unit =
    val stream = Option(getClass.getResourceAsStream(s"/scalahdl/verilator/$name"))
      .getOrElse(fail(s"missing resource $name"))
    try
      val _ = Files.copy(stream, target)
    finally stream.close()

  test("the switch FSM and the `when` FSM it replaces agree cycle by cycle"):
    assume(Seq("sh", "-c", "command -v verilator >/dev/null 2>&1").! == 0,
      "Verilator not on PATH; skipping R22's simulation criterion")

    val temp = Files.createTempDirectory("scalahdl-switch-oracle-")
    val emission = new Emission("0.1.1")

    val switchForm = emission.emit((new FetchFsm).elaborateDesign())
    val whenForm = emission.emit((new FetchFsmReference).elaborateDesign())
    assertEquals(switchForm.files.map(_._1), List("FetchFsm.v"))
    assertEquals(whenForm.files.map(_._1), List("FetchFsmReference.v"))

    // The claim under test is about the emitted `case`, so assert it is there
    // before spending a Verilator build on it.
    assert(switchForm.files.head._2.contains("case (fetchState)"),
      "the switch form must emit a native case")
    assert(!whenForm.files.head._2.contains("case ("),
      "the reference form must not, or the two are not different implementations")

    (switchForm.files ++ whenForm.files).foreach { case (name, text) =>
      Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8)
    }
    copyResource("fetch_fsm_equiv.v", temp.resolve("fetch_fsm_equiv.v"))
    copyResource("fetch_fsm_tb.cpp", temp.resolve("fetch_fsm_tb.cpp"))

    val build = Seq("verilator", "--cc", "--exe", "--build",
      "--top-module", "fetch_fsm_equiv",
      "fetch_fsm_equiv.v", "FetchFsm.v", "FetchFsmReference.v", "fetch_fsm_tb.cpp")
    val buildLog = new StringBuilder
    val buildCode = Process(build, temp.toFile).!(ProcessLogger(
      line => buildLog.append(line).append('\n'),
      line => buildLog.append(line).append('\n')))
    assertEquals(buildCode, 0, s"verilator build failed:\n$buildLog")

    val simLog = new StringBuilder
    val simCode = Process(Seq(temp.resolve("obj_dir/Vfetch_fsm_equiv").toString), temp.toFile).!(
      ProcessLogger(line => simLog.append(line).append('\n'),
                    line => simLog.append(line).append('\n')))
    assertEquals(simCode, 0, s"the two forms disagreed:\n$simLog")
    assert(simLog.toString.contains("both forms agreed on every output"), simLog.toString)

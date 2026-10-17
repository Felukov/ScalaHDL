package scalahdl.golden

import java.nio.file.Files
import scala.sys.process.*
import scalahdl.examples.{CpuAddressMap, CpuBankMap, CpuBridge, CpuScratchMap,
  NestedModules, ParameterizedModules}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** R23 — a multi-module design's emitted files elaborate into Verilog that
  * Verilator accepts *as a hierarchy*, not merely one file at a time.
  *
  * Linting the whole file set is the point: an instantiation whose connections
  * name ports the child does not have, or which leaves a port unconnected, is
  * invisible when each file is linted alone and is exactly what R23's
  * connection rules are responsible for. */
class HierarchyLintSuite extends munit.FunSuite:

  private def lintHierarchy(top: => Module, topModule: String): Unit =
    assume(Seq("sh", "-c", "command -v verilator >/dev/null 2>&1").! == 0,
      "Verilator not on PATH; skipping R23's hierarchy lint")
    val emitted = new Emission().emit(top.elaborateDesign())
    val dir = Files.createTempDirectory("scalahdl-hierarchy-lint")
    try
      new Emission().writeTo(emitted, dir.toString)
      val files = emitted.files.map(f => dir.resolve(f._1).toString)
      val output = new StringBuilder
      val command = Seq("verilator", "--lint-only", "--language", "1364-2001",
        "-Wall", "-Wno-DECLFILENAME", "--top-module", topModule) ++ files
      val rc = Process(command).!(ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')))
      assertEquals(rc, 0, s"verilator rejected the $topModule hierarchy:\n$output")
    finally
      val _ = Seq("rm", "-rf", dir.toString).!

  test("a two-level design with connected data ports lints as a hierarchy"):
    lintHierarchy(new ParameterizedModules, "ParameterizedModules")

  test("a three-level design lints as a hierarchy"):
    lintHierarchy(new NestedModules, "NestedModules")

  test("bundle ports connected whole lint as a hierarchy"):
    lintHierarchy(new CpuBridge, "CpuBridge")

  test("an instance array with Vec-carried buses lints as a hierarchy"):
    lintHierarchy(new CpuAddressMap, "CpuAddressMap_n3")

  // The same two `CpuUtils` calls at two widths and two slot counts. A helper
  // that builds into the caller's module has no separate output to check, so
  // linting the designs it built is the check.
  test("a hierarchy built by library helpers lints at an 8-bit bus"):
    lintHierarchy(new CpuScratchMap, "CpuScratchMap_n4")

  test("a hierarchy built by the same helpers lints at a 16-bit bus"):
    lintHierarchy(new CpuBankMap, "CpuBankMap_n2")

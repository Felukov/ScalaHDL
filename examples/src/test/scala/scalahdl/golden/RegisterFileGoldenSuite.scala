package scalahdl.golden

import java.nio.file.Files
import scala.sys.process.*
import scalahdl.examples.RegisterFile
import scalahdl.emit.VerilogEmitter

/** M05 — the register-file example emits reproducibly and lints clean.
  *
  * The aggregate emission *shape* — the struct-of-arrays net arrays, the
  * anonymous `Mux`, the runtime-index write — is the emitter's contract and is
  * pinned by `core`'s `DecodedVecPort` golden, on a `core`-local design
  * built for it. This suite keeps the example-side promise: emission is
  * deterministic and Verilator accepts the output. */
class RegisterFileGoldenSuite extends munit.FunSuite:

  def emitted(): String =
    val design = (new RegisterFile).elaborateDesign()
    new VerilogEmitter("0.1.2").emitModule(design.top, "RegisterFile")

  test("aggregate emission is deterministic"):
    assertEquals(emitted(), emitted())

  test("RegisterFile aggregate output passes Verilator lint"):
    assume(Seq("sh", "-c", "command -v verilator >/dev/null 2>&1").! == 0,
      "Verilator not on PATH; skipping M05's lint check")
    val dir = Files.createTempDirectory("scalahdl-register-file")
    try
      val path = dir.resolve("RegisterFile.v")
      Files.writeString(path, emitted())
      val rc = Seq("verilator", "--lint-only", "-Wall", "-Wno-fatal", path.toString).!
      assertEquals(rc, 0, s"Verilator rejected ${path.toString}")
    finally
      val _ = Seq("rm", "-rf", dir.toString).!

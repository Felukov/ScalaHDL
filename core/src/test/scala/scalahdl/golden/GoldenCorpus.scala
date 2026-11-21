package scalahdl.golden

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

import scalahdl.elab.Module
import scalahdl.emit.{Emission, Vendor, VerilogEmitter}

/** One golden corpus, one runner, byte-exact comparison.
  *
  * A golden pins the emitted Verilog of one construction. The registry maps a
  * golden's name to the `Module` that produces it; the runner elaborates that
  * module, emits it, and compares the bytes with the file on disk. Every
  * emission test uses this runner, so there is one place that knows how a
  * golden is read and compared.
  *
  * **The runner fails on two things, not one.** A *missing* golden — a
  * registered construction with no file — fails, because the corpus is
  * incomplete. An *unreferenced* golden — a file no registry entry produces —
  * also fails, and that is what stops the corpus rotting: a renamed entry or a
  * deleted design leaves a file behind, and nothing else would notice. The
  * second check is `checkOrphans`.
  *
  * **The runner only reads.** A golden is an authored expectation: a person
  * states what the emitter must write, and the runner compares the emitter with
  * that statement. Nothing in a test run can write a golden, so a test run can
  * never agree with itself. When the emitter's output moves, one of the two is
  * wrong — the emitter or the stated expectation — and a person says which and
  * edits the golden by hand. That edit is the review, and it is the only way the
  * expectation changes.
  *
  * **Byte-exactness is the point, not a defect.** R12 makes the emission format
  * contractual, so the runner does not normalise, and it does not use pattern
  * or `FileCheck` assertions or a Verilator AST comparison. The bytes are the
  * contract.
  *
  * ==One corpus, `core`-only==
  * Every construction is pinned by a small design declared in `core`'s tests,
  * under `core/src/test/resources/scalahdl/golden/`. The examples *demonstrate*
  * and may be edited for teaching, so they are not the emitter's contract and
  * hold no goldens of their own; there is one runner and one corpus. */
trait GoldenCorpus extends munit.FunSuite:

  /** The version the R20.2 header carries. Every checked-in golden carries the
    * build's own version in its header. */
  protected final val version: String = "0.1.2"

  /** The directory holding this module's golden corpus, relative to the working
    * directory sbt runs the tests from. */
  protected def corpusRoot: Path

  /** Register every entry, and the orphan check that keeps the corpus honest. */
  protected final def registerCorpus(entries: List[Golden]): Unit =
    entries.foreach { entry =>
      test(s"golden ${entry.name}") { check(entry) }
    }
    test("every golden file in the corpus is produced by a registry entry") {
      checkOrphans(entries)
    }

  private def check(entry: Golden): Unit =
    val actual = emit(entry)
    val path = corpusRoot.resolve(s"${entry.name}.v")
    assert(Files.exists(path), s"missing golden: $path has no file")
    val expected = Files.readString(path)
    if actual != expected then
      fail(s"golden mismatch: $path\n${diff(expected, actual)}")

  private def emit(entry: Golden): String =
    val design = entry.module().elaborateDesign()
    val generator = entry.generator.getOrElse(design.generation.generatorClass)
    new VerilogEmitter(version, entry.vendor).emitModule(design.top, generator)

  /** A file no registry entry produces is a stale golden. */
  private def checkOrphans(entries: List[Golden]): Unit =
    val produced = entries.map(entry => s"${entry.name}.v").toSet
    val orphans = corpusFiles() -- produced
    assert(orphans.isEmpty,
      s"unreferenced goldens: ${orphans.toList.sorted.mkString(", ")} — " +
        "every golden must be produced by a registry entry")

  /** Every `.v` file under the corpus root, as a path relative to it. */
  protected final def corpusFiles(): Set[String] =
    if !Files.exists(corpusRoot) then Set.empty
    else
      val stream = Files.walk(corpusRoot)
      try
        stream.iterator().asScala
          .filter(Files.isRegularFile(_))
          .map(corpusRoot.relativize(_).toString)
          .filter(_.endsWith(".v"))
          .toSet
      finally stream.close()

  /** The first line that differs, with one line of context either side. A
    * byte-exact comparison needs no cleverer report than a pointer at the row
    * that moved. */
  private def diff(expected: String, actual: String): String =
    val e = expected.linesIterator.toList
    val a = actual.linesIterator.toList
    val index = e.zipAll(a, "", "").indexWhere((l, r) => l != r)
    if index < 0 then "  (files differ only in length)"
    else
      val ctx = 1
      val from = math.max(0, index - ctx)
      val to = math.min(math.max(e.length, a.length), index + ctx + 1)
      val rows = (from until to).map { i =>
        val exp = e.lift(i).getOrElse("<absent>")
        val act = a.lift(i).getOrElse("<absent>")
        val mark = if exp == act then "  " else "! "
        f"$mark${i + 1}%4d golden: $exp%s\n      emitted: $act%s"
      }
      rows.mkString("\n")

/** One registered construction: the name it holds in the corpus, the module
  * that produces it, and the two emission choices the golden pins.
  *
  * `generator` overrides the header's `Generator:` line. The default is the
  * top generator class name the design captured (R20.2); a design declared
  * inside a suite and emitted with its module name names that module instead.
  * `vendor` names the synthesis tool whose memory hint the golden holds (R15.7). */
final case class Golden(name: String, module: () => Module,
                        generator: Option[String] = None,
                        vendor: Option[Vendor] = None)

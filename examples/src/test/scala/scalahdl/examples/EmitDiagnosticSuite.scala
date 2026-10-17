package scalahdl.examples

import java.nio.file.{Files, Path, Paths}
import java.util.Comparator

import scalahdl.diag.ValidationFailed

/** R36 / H1 — the excerpt/caret block the generator promises actually renders.
  *
  * `Emit.rejected` is the exact render the generator prints when a design does
  * not validate: it supplies the source root the generator computes and calls
  * `ValidationFailed.fullRender`. `WidthMismatches.AssignUIntWidths` is a real
  * example that fails validation, and it lives in the directory the root names
  * (`examples/src/main/scala/scalahdl/examples`), so finding its source line
  * here proves the root resolves the file `Loc.file` carries (`Loc.file` is
  * only a bare name).
  *
  * The second assertion pins the reason the two render paths exist: the raw
  * exception message must not do file I/O (it uses `ExcerptSource.none`), so
  * the excerpt is present only in the generator's render.
  *
  * The later tests drive `Emit.excerptSourceFor` directly. The fallback cannot
  * be reached by changing the working directory of this test JVM without
  * leaving the repository root that the first test depends on, so the
  * resolution is driven with its own base instead.
  */
class EmitDiagnosticSuite extends munit.FunSuite:

  test("a rejected design renders with the caret block, resolving the source root"):
    val ex = intercept[ValidationFailed] {
      new WidthMismatches.AssignUIntWidths().elaborateDesign()
    }
    // The diagnostic's primary location names the file the generator's root
    // must resolve.
    assertEquals(ex.diagnostics.head.primaryLoc.file, "WidthMismatches.scala")
    val rendered = Emit.rejected(ex)
    assert(rendered.contains("  --> WidthMismatches.scala:"),
      s"primary location missing:\n$rendered")
    assert(rendered.contains("wide := narrow"),
      s"the source excerpt did not render — the root did not resolve:\n$rendered")
    assert(rendered.contains("^"),
      s"the caret did not render:\n$rendered")

  test("the raw exception message stays I/O-free; only fullRender adds the excerpt"):
    val ex = intercept[ValidationFailed] {
      new WidthMismatches.AssignUIntWidths().elaborateDesign()
    }
    // The caret is the marker of the excerpt block: it appears only when a
    // source line is read. The `summary`/`fix` text may name the signals, so
    // the caret — not the code text — is what distinguishes the two renders.
    assert(!ex.getMessage.contains("^"),
      s"the default getMessage must not read source (ExcerptSource.none):\n${ex.getMessage}")
    assert(Emit.rejected(ex).contains("^"),
      s"fullRender must draw the caret:\n${Emit.rejected(ex)}")

  test("the documented layout is preferred when it holds the named file"):
    // Both a step-1 directory and a decoy elsewhere hold the name; the join
    // must win, which is what keeps the repository-root run unchanged.
    withTempDir { base =>
      val layout = Files.createDirectories(
        base.resolve("examples/src/main/scala/scalahdl/examples"))
      Files.writeString(layout.resolve("Decoy.scala"), "val from_layout = 1\n")
      val decoy = Files.createDirectories(base.resolve("elsewhere"))
      Files.writeString(decoy.resolve("Decoy.scala"), "val from_search = 1\n")

      val src = Emit.excerptSourceFor("Decoy.scala", base)
      assertEquals(src.lineText("Decoy.scala", 1), Some("val from_layout = 1"))
    }

  test("the search finds the named file when the repository layout does not apply"):
    // No step-1 directory under this base, so resolution must fall through to
    // the bounded search and still read the file the diagnostic names.
    withTempDir { base =>
      val nested = Files.createDirectories(base.resolve("a/b/c"))
      Files.writeString(nested.resolve("Decoy.scala"), "val wrong = line\n")

      val src = Emit.excerptSourceFor("Decoy.scala", base)
      assertEquals(src.lineText("Decoy.scala", 1), Some("val wrong = line"))
    }

  test("an ambiguous name resolves to no excerpt rather than an arbitrary one"):
    withTempDir { base =>
      Files.writeString(
        Files.createDirectories(base.resolve("a")).resolve("Decoy.scala"), "one\n")
      Files.writeString(
        Files.createDirectories(base.resolve("b")).resolve("Decoy.scala"), "two\n")

      val src = Emit.excerptSourceFor("Decoy.scala", base)
      assertEquals(src.lineText("Decoy.scala", 1), None)
    }

  test("the real failing design resolves through the fallback from a non-root base"):
    // `examples` is not the repository root, so step 1 misses its nested layout
    // and the search is what finds the real `WidthMismatches.scala`.
    val ex = intercept[ValidationFailed] {
      new WidthMismatches.AssignUIntWidths().elaborateDesign()
    }
    val src = Emit.excerptSourceFor("WidthMismatches.scala", Paths.get("examples"))
    assert(src.lineText("WidthMismatches.scala", 1).isDefined,
      "the fallback did not find the design's source from a non-root base")

  /** A fresh directory handed to `f` and removed afterwards, so the resolution
    * tests do not depend on the working directory or leave anything behind. */
  private def withTempDir[A](f: Path => A): A =
    val dir = Files.createTempDirectory("scalahdl-emit")
    try f(dir)
    finally
      val walk = Files.walk(dir)
      try
        walk.sorted(Comparator.reverseOrder[Path]())
          .forEach(p => Files.deleteIfExists(p))
      finally walk.close()

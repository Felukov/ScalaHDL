package scalahdl.diag

import scala.collection.mutable
import java.nio.file.{Files, Path}

/** R19 — Source excerpts for the primary-location block. The renderer asks an
  * `ExcerptSource` for the text of a source line; if it has none, it emits the
  * `-->` location without the `n | …` excerpt + caret block (the degenerate
  * case). This keeps file I/O out of exception messages by default: the
  * `given ExcerptSource = ExcerptSource.none` instance returns `None` for
  * everything, and tests (or a tool driver) supply `ExcerptSource.fromDir`
  * pointed at the fixture/source root.
  *
  * `Loc.file` is just the source file *name* (the R1 macro captures
  * `sourceFile.name`), so a directory is resolved by `<dir>/<file>`. Lines are
  * fetched lazily and cached per path — the cache holds the parsed lines of any
  * file a diagnostic touched, never the whole tree. */
trait ExcerptSource:
  /** The 1-based source line, without its trailing newline, or `None` if the
    * file/line is unavailable. */
  def lineText(file: String, line: Int): Option[String]

object ExcerptSource:

  /** The default: no excerpts. Used by `ValidationFailed.getMessage` so an
    * uncaught exception never does file I/O. */
  given none: ExcerptSource = (file: String, line: Int) => None

  /** An excerpt source that reads files from a directory, caching parsed
    * lines per file. `dir` is the root; `file` is the bare name carried by
    * `Loc` (e.g. `Fifo.scala`). */
  final class FromDir(dir: Path) extends ExcerptSource:
    private val cache = mutable.Map.empty[String, Vector[String]]
    private def linesOf(file: String): Option[Vector[String]] =
      cache.get(file) match
        case Some(v) => Some(v)
        case None =>
          val path = dir.resolve(file)
          if !Files.exists(path) then None
          else
            val v = Files.readAllLines(path).nn.toArray(new Array[String](0)).toVector
            cache(file) = v
            Some(v)
    def lineText(file: String, line: Int): Option[String] =
      linesOf(file).flatMap(v => if line >= 1 && line <= v.length then Some(v(line - 1)) else None)

  /** Build a directory-backed excerpt source. */
  def fromDir(dir: Path): ExcerptSource = FromDir(dir)
end ExcerptSource

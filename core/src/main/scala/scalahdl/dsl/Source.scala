package scalahdl.dsl

import scalahdl.core.Loc
import scala.quoted.*

/** R1 — Name preservation via `Symbol.spliceOwner`; no compiler plugin.
  *
  * This is the bet (Appendix C): that an `inline def` factory inspecting
  * `Symbol.spliceOwner` recovers the binding `val`'s name at compile time,
  * for a `val` declared at module top level, inside a method body, and inside
  * a loop body — the three locations field reflection cannot reach.
  *
  * Mechanism, verified empirically on Scala 3.3.3 and re-checked on 3.3.8: at
  * an inline-splice site `val cnt = UInt(8)`, `Symbol.spliceOwner` is the
  * synthetic `macro` ValDef the compiler creates for the splice, and its
  * **owner** is `cnt` — the user's binding symbol. So:
  *
  *   - `spliceOwner.owner` is the binding `val` → name known, location known.
  *   - when the splice is *not* bound to a `val` (assigned to an existing
  *     var, passed as an argument, used as a bare statement), the owner is a
  *     compiler-generated `<local ...>` symbol → definitively anonymous,
  *     never guessed at (R2). Anonymous trees inline at every use site.
  *
  * `Source` is the result of capture: an optional name plus a `Loc`. Factory
  * methods in `dsl` call `captureSource` and thread the result into the IR. */

/** The compile-time capture: `Some(name)` for a user `val`, `None` anonymous. */
final case class Source(name: Option[String], loc: Loc)

object Source:

  /** The inline entry point. Every hardware factory (`UInt`, `Reg`, `In`, ...)
    * calls this so name capture is centralised and uniform. */
  inline def capture: Source = ${ captureImpl }

  def captureImpl(using q: Quotes): Expr[Source] =
    import q.reflect.*
    val splice = Symbol.spliceOwner
    val owner  = splice.owner
    // A user val: a ValDef whose name is not compiler-synthetic (does not
    // start with '<'). Anything else is anonymous per R2.
    val isUserVal = owner.isValDef && !owner.name.startsWith("<")
    val nameOpt   = if isUserVal then Some(owner.name) else None
    val loc =
      try Loc.fromSymbolPos(owner)
      catch case _: Throwable => Loc.NoLoc
    // nameOpt is a compile-time constant; lift it as a known Some/None.
    // Loc has no ToExpr instance, so lift its fields and reconstruct.
    val locExpr = '{ Loc(${ Expr(loc.file) }, ${ Expr(loc.line) }, ${ Expr(loc.col) }) }
    nameOpt match
      case Some(n) => '{ Source(Some(${ Expr(n) }), $locExpr) }
      case None    => '{ Source(None, $locExpr) }

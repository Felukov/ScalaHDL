package scalahdl.core

/** R24 — the named encoding a signal was declared from.
  *
  * This is metadata, not a type. `In(AluOp())` declares an ordinary
  * `Type.UInt(4)` signal and records here that its bit patterns have names, so
  * an R11/R22 diagnostic can say `8 (AluOp.Or)` instead of `8`. No validation
  * phase and no emitter reads it — an encoding that reached the type system
  * would be a different requirement, and R24 says so explicitly.
  *
  * `members` keeps declaration order. Anonymous members (a `Value` not bound to
  * a `val`, so R1 captured no name) are absent: a name is reported only where
  * the user wrote one. */
final case class EncodingInfo(name: String, width: Int, members: List[(BigInt, String)]):

  private lazy val byCode: Map[BigInt, String] = members.toMap

  /** The member's own name, e.g. `Or`. */
  def memberName(code: BigInt): Option[String] = byCode.get(code)

  /** The name a diagnostic prints, e.g. `AluOp.Or`. */
  def qualified(code: BigInt): Option[String] = memberName(code).map(member => s"$name.$member")

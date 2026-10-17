package scalahdl.core

/** R18.3 — A typed external-module parameter. Values stay typed in the IR so
  * backends, rather than elaboration, control their literal rendering. */
enum BlackBoxParamValue:
  case Integer(value: Int)
  case Real(value: Double)
  case Str(value: String)

final case class BlackBoxParam(name: String, value: BlackBoxParamValue)

/** R18.1 — The signature used to ensure every declaration claiming a fixed
  * external module name agrees on its ordered, case-sensitive port contract. */
final case class BlackBoxPortSignature(
  name: String,
  direction: Direction,
  typeOf: Type
)

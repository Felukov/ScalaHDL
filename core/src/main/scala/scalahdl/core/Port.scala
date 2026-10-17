package scalahdl.core

/** R10 — Port. Name, direction, type, declaration location. Verilog port
  * names are exactly the `val` names (R1) — no prefix to strip. Ports appear
  * in the IR `Module` in declaration order; the emitter writes them in that
  * order. A port's type may be scalar or aggregate (R07.1/R14.1); the clock
  * and reset it belongs to is the domain the signal carries (R13.1). */
final case class Port(name: String, direction: Direction, typeOf: Type, loc: Loc)

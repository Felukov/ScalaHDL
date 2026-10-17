package scalahdl.core

/** R17 / R13.2 — A submodule instance. Name (the `val` name via R1), target
  * module name, port connections (port name → expression), and declaration
  * location. Port connections include both user-written assignments and the
  * auto-connected clock/reset from the enclosing domain (R13.2).
  *
  * The instance references its target by name (not by object), so the IR
  * graph stays acyclic — the emitter resolves the name to the emitted module
  * at emission time. */
final case class Instance(
  name: String,
  target: String,
  connections: List[(String, Expr)],
  loc: Loc,
  parameters: List[BlackBoxParam] = Nil,
  external: Boolean = false,
  /** R18.5 — the ports of an *external* module that are unpacked arrays and
    * therefore connect whole, by name, rather than leaf by leaf. Empty for a
    * generated child, which has no such port by R14.1. */
  wholePorts: Set[String] = Set.empty,
  /** R28 — the child's ports this instance *drives*. `connections` carries
    * inputs and outputs alike and the IR loses which is which, and anything
    * reasoning across the boundary needs the direction: an output's connection
    * is the parent net the child writes, an input's is an expression the
    * parent computes. */
  outputs: Set[String] = Set.empty,
  /** R28 — declared `input -> output` pairs an *external* module carries
    * combinationally. A blackbox has no body to analyse, so this is the one
    * thing about it that cannot be known by looking; empty means none, which
    * is the assumption the graph records rather than makes silently. A
    * generated child needs no declaration — its own graph answers. */
  combPaths: Set[(String, String)] = Set.empty
)

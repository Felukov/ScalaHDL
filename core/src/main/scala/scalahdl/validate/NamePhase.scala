package scalahdl.validate

import scalahdl.core.*
import SignalId.*
import scalahdl.diag.{Code, Diagnostic, Severity, RelatedLoc}
import scalahdl.emit.LeafName
import scala.collection.mutable

/** R1 — name collisions. A named Scala `val` that becomes a signal appears in
  * the emitted Verilog under that name, unmangled. Two declarations producing
  * the same emitted (leaf) name is an elaboration error naming both
  * declaration lines.
  *
  * This is the collision half of R1; module identity (R16.1) is checked at
  * elaboration, not here. The check is over *flattened leaf names*, not raw
  * signal names.
  * A scalar signal contributes one leaf (its name); an aggregate (Bundle/Vec)
  * contributes its leaves via `LeafName` (e.g. `req_addr`, `req_prot`,
  * `entries_0_addr`). Two paths producing the same leaf name — e.g. `val a_b`
  * and `val a = Bundle { val b }` — is a `NAME_COLLISION` under R1.
  *
  * R1.1: a name the emitted Verilog would carry that is a SystemVerilog
  * keyword ([[ReservedWords]]) is `NAME_RESERVED` — a leaf of a signal or a
  * port, an instance, a memory, and the module's own name. Leaves and not
  * roots, because a bundle `accept` with a field `on` emits `accept_on`. The
  * names the emitter composes itself (`inst_port`, `name_0`) are built from
  * these and need no check of their own. */
final class NamePhase extends Validator.Phase:

  def run(ctx: Validator.Ctx): List[Diagnostic] =
    // Ports are also recorded as signals by the builder (a port is a signal
    // with a direction), so `module.signals` already contains them. Expand
    // each signal to its leaf names; a leaf appearing in two declarations is
    // a collision.
    val portNames = ctx.module.ports.iterator.map(_.name).toSet
    val decls = ctx.module.signals.flatMap { s =>
      val isPort = portNames.contains(s.name)
      val kind = if isPort then "port" else "signal"
      if isPort then
        LeafName.leaves(s.name, s.typeOf).map { case (leaf, _) => Decl(leaf, s.loc, kind, s.name) }
      else
        // Internal Vec dimensions are arrays and do not contribute numeric
        // name segments. Check exactly the names the backend declares.
        LeafName.internalLeaves(s.name, s.typeOf).map(l => Decl(l.name, s.loc, kind, s.name))
    }.toList
    val byName = decls.groupBy(_.name).filter(_._2.length > 1)
    val collisions = byName.toList.map { (name, group) =>
      val sorted = group.sortBy(_.loc.line)
      Diagnostic(
        severity = Severity.Error,
        code = Code.NAME_COLLISION,
        summary = s"`$name` is declared ${group.length} times (signals must have unique names)",
        primaryLoc = sorted.head.loc,
        related = sorted.tail.map(d => RelatedLoc(d.loc, s"`$name` declared here as a ${d.kind}")),
        fix = Some(s"rename one of the declarations"))
    }
    val named = decls ++
      ctx.module.instances.map(i => Decl(i.name, i.loc, "instance", i.name)) ++
      ctx.module.memories.map(m => Decl(m.name, m.loc, "memory", m.name)) :+
      Decl(ctx.module.name, ctx.module.loc, "module", ctx.module.name)
    val reserved = named.filter(d => ReservedWords.reserved(d.name))
      .distinctBy(d => (d.name, d.kind, d.root))
      .map { d =>
        val article = if "aeiou".contains(d.kind.head) then "an" else "a"
        val what = if d.root == d.name then s"$article ${d.kind}" else s"a leaf of the ${d.kind} `${d.root}`"
        Diagnostic(
          severity = Severity.Error,
          code = Code.NAME_RESERVED,
          summary = s"`${d.name}` is a SystemVerilog keyword and cannot name $what",
          primaryLoc = d.loc,
          fix = Some("rename it: the emitted Verilog would not parse"))
      }
    collisions ++ reserved

  private case class Decl(name: String, loc: Loc, kind: String, root: String)
end NamePhase

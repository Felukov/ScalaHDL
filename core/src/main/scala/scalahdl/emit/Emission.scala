package scalahdl.emit

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.nio.charset.StandardCharsets
import scalahdl.core.*

/** R20 — File organisation. One file per emitted module, filename matching the
  * module name (R16), so filename and module name always agree. A manifest
  * lists files in dependency order.
  *
  * R20.1 — Determinism: emission order is depth-first from the top module,
  * children before parents, ties broken by declaration order. Never map
  * iteration order — ordered collections throughout.
  *
  * R20.2 — reproducible header carried by `VerilogEmitter`; no timestamps.
  *
  * The whole emission is a pure function of the `Design`: identical input
  * yields identical bytes (R17 immutability + R20.2). */

/** Result of emitting a design: Verilog files, CDC reports, and manifest in
  * deterministic order. Reports are separate so the Verilog file API remains
  * source-compatible while `writeTo` places both artifacts side by side. */
final case class Emitted(files: List[(String, String)], manifest: String,
                         reports: List[(String, String)] = Nil)

/** `vendor` names whose synthesis tool the Verilog is written for (R15.7):
  * a memory carries the hint that tool reads, or none when no vendor is named. */
final class Emission(version: String = scalahdl.BuildInfo.version,
                     vendor: Option[Vendor] = None):

  private val emitter = new VerilogEmitter(version, vendor)

  /** Emit a `Design` to an `Emitted` (in-memory). Header provenance was
    * captured from the top generator during elaboration (R20.2). */
  def emit(design: Design): Emitted =
    // R20.1: depth-first, children-before-parents, ties by declaration order.
    // The traversal walks the reachable modules DFS, children first.
    val ordered = orderModules(design)
    val files = ordered.map { m =>
      (s"${m.name}.v", emitter.emitModule(m, design.generation))
    }
    // R29 — one graph per module, built children-first so a parent's cone can
    // cross an instance carrying the depth the child's own summary reported
    // (R28). `orderModules` already produces that order for R20.1.
    val graphs = scala.collection.mutable.LinkedHashMap.empty[String, CombGraph.Graph]
    ordered.foreach { m =>
      graphs(m.name) = CombGraph.build(m, graphs.view.mapValues(_.summary).toMap)
    }
    // R5.1 and R29 for every module; R25 only for the modules that declare a
    // machine, since an FSM report for a module with no FSM is a file saying
    // nothing.
    val reports = ordered.map { m =>
      (s"${m.name}.cdc.rpt", CdcReport.render(m))
    } ++ ordered.map { m =>
      (s"${m.name}.levels.rpt", LevelReport.render(m, graphs(m.name)))
    } ++ List(
      "design.levels.rpt" ->
        LevelReport.rollUp(design.top.name, ordered.map(m => m -> graphs(m.name)))
    ) ++ ordered.filter(FsmReport.applies).map { m =>
      (s"${m.name}.fsm.md", FsmReport.render(m, emitter))
    }
    val manifest = files.map(_._1).mkString("\n") + "\n"
    Emitted(files, manifest, reports)

  /** Compatibility entry point for manually assembled pre-R20.2 designs.
    * Elaborated designs already carry their exact top generator class. */
  def emit(design: Design, generatorClass: String): Emitted =
    val generation = design.generation match
      case GenerationInfo.Unknown => GenerationInfo(generatorClass, Nil)
      case captured => captured
    emit(design.copy(generation = generation))

  /** Write an `Emitted` to a directory. */
  def writeTo(emitted: Emitted, dir: String): Unit =
    val d = Paths.get(dir)
    Files.createDirectories(d)
    (emitted.files ++ emitted.reports).foreach { case (name, content) =>
      val p = d.resolve(name)
      Files.write(p, content.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
    Files.write(d.resolve("manifest.f"), emitted.manifest.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

  /** Deterministic module ordering (R20.1). Walk the IR graph from `top`, using
    * each module's ordered instance list as its child list. Mark a module before
    * descending so a malformed cyclic IR still terminates; append it only after
    * all children, producing DFS post-order. External instances have no emitted
    * module body and therefore do not participate in this traversal. */
  private def orderModules(design: Design): List[Module] =
    def visit(module: Module, seen: List[String], result: List[Module]):
        (List[String], List[Module]) =
      if seen.contains(module.name) then (seen, result)
      else
        val marked = module.name :: seen
        val (afterChildren, childResult) = module.instances.foldLeft((marked, result)) {
          case (state, instance) if instance.external => state
          case ((visited, ordered), instance) =>
            design.modules.find(_.name == instance.target) match
              case Some(child) => visit(child, visited, ordered)
              case None => throw EmitterError(
                s"internal instance `${instance.name}` targets missing module `${instance.target}`")
        }
        (afterChildren, childResult :+ module)

    visit(design.top, Nil, Nil)._2

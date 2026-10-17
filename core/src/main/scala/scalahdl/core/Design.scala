package scalahdl.core

/** R20.2 — Stable provenance carried from elaboration to every emitted file.
  * Parameters are the top generator's ordered, name-contributing R16 segments;
  * they are already sanitized and contain no runtime-specific value rendering. */
final case class GenerationInfo(generatorClass: String, parameters: List[String])

object GenerationInfo:
  val Unknown: GenerationInfo = GenerationInfo("unknown", Nil)

/** R17 — Design. The elaboration result: a top module plus every distinct
  * module reachable from it, deduplicated by R16. `top` is also an element of
  * `modules`, which carries the whole reachable hierarchy.
  *
  * The IR is one representation, no stages (R17): backends read it, nothing
  * rewrites it. `Design` is immutable after elaboration — the emitter must not
  * mutate it (R17 exit criterion: hash before and after emission, unchanged).
  * `generation` is immutable reproducible-header metadata captured from the top
  * generator during elaboration (R20.2). */
final case class Design(top: Module, modules: List[Module],
                        generation: GenerationInfo = GenerationInfo.Unknown)

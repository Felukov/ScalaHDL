package scalahdl

import scalahdl.validate.{Validator, WidthPhase, CompletenessPhase, CyclePhase, NamePhase, VecWritePhase, CdcPhase, SwitchPhase, PowerUpPhase, NeverRunsPhase}

/** The ordered validation phases (R17). A failing phase halts; the runner
  * does not invoke the next. Module identity (R16.1) is checked at elaboration
  * (`elab.Module`), not as a phase here. */
object Module:
  val validationPhases: List[Validator.Phase] = List(
    new WidthPhase,          // R06 — widths + signedness
    new SwitchPhase,         // R22 — case values: constant, typed, distinct
    new VecWritePhase,       // R14 path/index legality precedes coverage checks
    new PowerUpPhase,        // R13.4 — an init in a domain with no reset is a
                             // declaration initialiser, so it must be one
    new CompletenessPhase,   // R11 / R11.1 — combinational completeness, Reg exempt
    new CyclePhase,          // R28 — and that what drives it settles; after R11
                             // so an undriven wire is reported as one
    new CdcPhase,            // R05 — foreign-domain reads require explicit primitives
    new NeverRunsPhase,      // R12.1 — a combinational block nothing can trigger
    new NamePhase            // R1  — name collisions (minimal)
  )

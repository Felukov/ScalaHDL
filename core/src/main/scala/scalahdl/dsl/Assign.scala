package scalahdl.dsl

import scalahdl.core.*
import scalahdl.diag.{Code, Diagnostic, RelatedLoc, Severity, ValidationFailed}
import scalahdl.elab.Module

/** R12 — Assignment. `target := expr` records an assignment statement at the
  * current condition scope. Blocking for combinational signals, non-blocking
  * for registers (the builder decides from `isReg`).
  *
  * `:=` is an extension method so it reads as Scala at the call site. It is
  * `inline` for one reason only: R19 requires a source location "on `:=`", and
  * an inline method is what lets `Loc.here` expand at the *user's* call site
  * rather than at this file's line 24. Without it every width and kind
  * diagnostic pointed at a declaration and had nothing to say about the line
  * that got it wrong. The body stays in `Assign.at` so the inlined form is one
  * call, not a copy of the checks below at every assignment in a design.
  *
  * R18/R23 — three targets are not assignments at all, and each is decided
  * here because this is where the target's module becomes known:
  *
  *   - an **instance input port**, whole or one leaf of an aggregate one,
  *     records a named connection on that instance — the boundary has one pin
  *     per leaf, so a leaf is a pin;
  *   - an **instance output port** is already driven by its instance (R12);
  *   - a signal owned by **another module** is a wrong-module write. Signal ids
  *     are per-builder and dense, so recording it would silently drive whichever
  *     of this module's signals shares the id. */
extension (target: DslSignal)
  inline def := (expr: Expr)(using m: Module): Unit =
    Assign.at(target, expr, Loc.here)

/** The assignment itself, with its source location passed rather than
  * captured. `:=` supplies the user's call site; a library helper that
  * assembles assignments of its own (`decode`, `unpackBits`) supplies `NoLoc`,
  * because the line inside the helper is not where a caller's mistake is. */
object Assign:

  def at(target: DslSignal, expr: Expr, loc: Loc)(using m: Module): Unit =
    target.instanceInput match
      case Some((parent, instanceIndex, portName)) =>
        require(parent eq m.builder,
          "an instance input must be connected in its parent module")
        if target.path.isEmpty then parent.connectInstance(instanceIndex, portName, expr)
        else parent.connectInstanceLeaf(instanceIndex, portName, target, expr, loc)
      case None =>
        target.instanceOutput.foreach(instanceName => throw ValidationFailed.one(Diagnostic(
          severity = Severity.Error,
          code = Code.INSTANCE_OUTPUT_ASSIGNED,
          summary = s"`${target.name}` is an output of instance `$instanceName` and is already driven by it",
          primaryLoc = loc,
          primaryNote = Some("assigned here"),
          fix = Some(s"read `$instanceName.${target.name}` instead of assigning it; to drive a net of your own, declare one and connect it to an input port"))))
        if !target.owner.forall(_ eq m.builder) then
          throw ValidationFailed.one(Diagnostic(
            severity = Severity.Error,
            code = Code.FOREIGN_SIGNAL_ASSIGNED,
            summary = s"`${target.name}` belongs to another module and cannot be assigned here",
            primaryLoc = loc,
            primaryNote = Some("assigned here"),
            related = List(RelatedLoc(Loc.NoLoc,
              "signal ids are per-module, so this would otherwise drive an unrelated signal of the assigning module")),
            fix = Some("instantiate the module with `inst(...)` and assign its input port, e.g. `val u = inst(new Child); u.port := expr`")))
        m.builder.addAssign(target, expr, loc)

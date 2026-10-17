package scalahdl.validate

import scalahdl.core.*
import SignalId.*
import scalahdl.diag.{Code, Diagnostic, Severity}

/** R13.4 / R13.8 — a register's two values, one argument each.
  *
  * `init` is the value a reset loads and `powerUp` is the value a bitstream
  * loads, whatever the domain. Before, a domain with no reset wrote `init` on
  * the declaration instead, so one argument meant either value depending on
  * the domain — and giving that domain a reset, or moving the register into
  * one, withdrew its value at configuration with no line of the source
  * changed. Now `init` in a domain with no reset is refused, and the fix names
  * `powerUp`, whose meaning does not depend on where the register lives.
  *
  * The declaration has two rules of its own — it is initialised with a
  * *constant*, and an aggregate is one net per leaf (R07.1/R14.1) with no
  * single declaration to carry a packed value — and `powerUp` is held to both.
  */
final class PowerUpPhase extends Validator.Phase:

  def run(ctx: Validator.Ctx): List[Diagnostic] =
    val types: SignalId => Type = id => ctx.signal(id).typeOf
    val declared = ctx.module.signals.filter(s => s.isReg && s.powerUp.isDefined).flatMap { s =>
      s.typeOf match
        case Type.Bundle(_) | Type.Vec(_, _) =>
          Some(Diagnostic(
            severity = Severity.Error,
            code = Code.POWER_UP_AGGREGATE,
            summary = s"`${s.name}` is an aggregate register with a power-up value",
            primaryLoc = s.loc,
            primaryNote = Some("an aggregate is emitted as one net per leaf, and a " +
                               "power-up value is written on a declaration"),
            fix = Some("declare the fields as scalar registers")))
        case _ if ExprConst.bits(s.powerUp.get, types).isEmpty =>
          Some(Diagnostic(
            severity = Severity.Error,
            code = Code.POWER_UP_NOT_CONSTANT,
            summary = s"the power-up value of `${s.name}` is not known at elaboration time",
            primaryLoc = s.loc,
            primaryNote = Some("a power-up value is written on the declaration, and a " +
                               "declaration is initialised with a constant"),
            fix = Some("make the power-up value a constant; only the *reset* value " +
                       "may be computed")))
        case _ => None
    }
    val unreset = ctx.module.signals.filter(s => s.isReg && s.init.isDefined &&
                                               !s.domain.hasReset).map { s =>
      Diagnostic(
        severity = Severity.Error,
        code = Code.INIT_WITHOUT_RESET,
        summary = s"`${s.name}` has an init in a clock domain that has no reset",
        primaryLoc = s.loc,
        primaryNote = Some("`init` is the value a reset loads, and this domain never " +
                           "resets"),
        fix = Some("write `powerUp = …` for the value the register holds at " +
                   "configuration, or give the domain a reset"))
    }
    declared ++ unreset

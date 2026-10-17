package scalahdl.core

import SignalId.*

/** R17 — Signal. Name, type, kind (wire/register), the clock domain it was
  * declared in (R4), reset value for registers (R13 — the *value* only; reset
  * style is a domain property), and the declaration location. Declaration
  * order is source order: the builder appends signals in the order `val`s are
  * evaluated.
  *
  * A signal does not reference its driver (R17.1). The `DriverBlock` holds the
  * back-reference `block → signal`; nothing holds `signal → block`.
  *
  * R24 — `encoding` is the named encoding the signal was declared from, when it
  * was. It is diagnostic metadata: the signal's `typeOf` is the plain `UInt` an
  * encoding stands for, and nothing outside the message renderers reads it. */
final case class Signal(
  id: SignalId,
  name: String,
  typeOf: Type,
  kind: SignalKind,
  init: Option[Expr],
  loc: Loc,
  /** R13.8 — the value a bitstream loads at configuration, written on the
    * declaration, independently of whether the domain has a reset. `init` is
    * the *reset* value; in a domain with no reset R13.4 already writes it on
    * the declaration and this stays empty. */
  powerUp: Option[Expr] = None,
  domain: scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock,
  encoding: Option[EncodingInfo] = None
):
  def isReg: Boolean = kind == SignalKind.Reg

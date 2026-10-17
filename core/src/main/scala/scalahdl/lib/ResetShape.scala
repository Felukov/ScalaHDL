package scalahdl.lib

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.elab.{ModuleArg, namedParam, param}

/** R16 — a library component's reset shape, as a module argument: always part
  * of its identity, and part of its name only when it is not the library's
  * default, asynchronous and active low. So `Skid(8)` is `Skid_w8` and
  * `Skid(8, ResetKind.Sync, ActiveHigh)` is `Skid_w8_rst_sync_high`. */
private[lib] object ResetShape:

  /** `refusal` is the component's reason it has no `ResetKind.None` shape. */
  def arg(kind: ResetKind, polarity: ResetPolarity, refusal: String): ModuleArg =
    val shape = (kind, polarity)
    encoding(kind, polarity, refusal) match
      case Some(name) => namedParam("rst", shape, name)
      case None => param("rst", shape)

  private def encoding(kind: ResetKind, polarity: ResetPolarity, refusal: String): Option[String] =
    val edge = kind match
      case ResetKind.Async => "async"
      case ResetKind.Sync => "sync"
      case ResetKind.None => throw IllegalArgumentException(refusal)
    val sense = polarity match
      case ActiveLow => "low"
      case ActiveHigh => "high"
    if kind == ResetKind.Async && polarity == ActiveLow then None
    else Some(s"${edge}_$sense")

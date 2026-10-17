package scalahdl.elab

/** R16/R16.1 — An explicit module-constructor argument used for stable naming
  * and identity. Every parameter participates in identity; only parameters
  * with an encoding participate in the derived Verilog module name. */
sealed trait ModuleArg

final class ModuleParam private[elab] (
    val name: String,
    val value: Any,
    val encoded: Option[String]
) extends ModuleArg:
  override def equals(other: Any): Boolean = other match
    case that: ModuleParam =>
      name == that.name && value == that.value && encoded == that.encoded
    case _ => false

  override def hashCode(): Int = (name, value, encoded).hashCode()

private[elab] final case class ModuleNameOverride(value: String) extends ModuleArg

private[elab] final case class ModulePrefixArg(value: String) extends ModuleArg

/** Declare a module parameter. Int, Boolean, and Scala enum values contribute
  * to the derived name by default. Other values remain identity-only. */
def param(name: String, value: Any): ModuleParam =
  ModuleParam(name, value, ModuleParam.defaultEncoding(name, value))

/** Opt a normally non-contributing value into naming with an explicit stable
  * encoding. The original value, not the encoding, is still used for identity. */
def namedParam(name: String, value: Any, encoding: String): ModuleParam =
  ModuleParam(name, value, Some(ModuleParam.segment(name, encoding)))

/** Supply an explicit emitted module name as a base-constructor argument. */
def moduleName(value: String): ModuleArg = ModuleNameOverride(value)

/** R16.2 — prefix the derived name of every module in a group.
  *
  * A Scala class is qualified by its package; an emitted module is not, because
  * Verilog has one flat namespace and the derived name is the class's simple
  * name. A prefix is how that qualification survives the trip: `m68k_Fifo_d16`
  * says which subsystem a module in a waveform belongs to, and two subsystems
  * may each hold a `Fifo` without meeting.
  *
  * Declared once on a base class the group extends, so a module states what it
  * is and inherits where it lives. The prefix is chosen rather than derived
  * from the package: granularity is a property of the design, and a name that
  * followed the source tree would rename a Verilog module every time a class
  * moved. An explicit `moduleName` is a whole name and takes no prefix. */
def modulePrefix(value: String): ModuleArg = ModulePrefixArg(ModuleParam.prefix(value))

object ModuleParam:
  private[elab] def apply(name: String, value: Any,
                          encoded: Option[String]): ModuleParam =
    require(name.nonEmpty, "module parameter name must not be empty")
    new ModuleParam(name, value, encoded)

  private[elab] def defaultEncoding(name: String, value: Any): Option[String] =
    value match
      case i: Int =>
        val encoded = if i < 0 then s"n${-(i.toLong)}" else i.toString
        Some(s"${sanitize(name)}$encoded")
      case b: Boolean => if b then Some(sanitize(name)) else None
      case t: scalahdl.core.Type => Some(s"${sanitize(name)}${t.bitWidth}")
      case e: scala.reflect.Enum => Some(segment(name, e.productPrefix))
      case _ => None

  private[elab] def prefix(value: String): String =
    require(value.nonEmpty, "a module prefix must not be empty")
    require(value.forall(c => c.isLetterOrDigit || c == '_'),
      "a module prefix is part of a Verilog identifier, so it holds letters, " +
      s"digits and underscore only: `$value`")
    require(!value.head.isDigit,
      s"a module prefix does not begin with a digit: `$value`")
    value

  private[elab] def segment(name: String, encoding: String): String =
    s"${sanitize(name)}_${sanitize(encoding)}"

  private[elab] def sanitize(value: String): String =
    val mapped = value.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    val collapsed = mapped.replaceAll("_+", "_").stripPrefix("_").stripSuffix("_")
    require(collapsed.nonEmpty, s"module-name component `$value` is empty after sanitizing")
    collapsed

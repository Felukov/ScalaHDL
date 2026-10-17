package scalahdl.core

/** R17 — Type. The type sum carried on ports and signals.
  *
  * Widths are values, not types (R6): they are checked at validation
  * (`WidthPhase`), not tracked at the Scala type level.
  *
  * `Clock` and `Reset(polarity)` are distinct from `Bool` at the Scala type
  * level (R13.1) so a clock cannot be used as data and a domain can only be
  * built from correctly-typed signals. The aggregates `Bundle` and `Vec`
  * (R07/R14) are *preserved* in the IR (R17) — the emitter resolves leaf
  * names per R07.1/R14.1 at write time, so the structure is recoverable by the
  * SystemVerilog packed-struct backend (R09). */
enum Type:
  case Bool
  case UInt(width: Int)
  case SInt(width: Int)
  case Bits(width: Int)
  /** R05.3 — Multi-bit value whose legal constructors preserve gray coding. */
  case GrayCode(width: Int)
  case Clock
  case Reset(polarity: ResetPolarity)
  case Bundle(fields: List[(String, Type)])
  case Vec(n: Int, elem: Type)

  /** Verilog width annotation. `Bool` is 1 bit. Returns the `[h:0]` form for
    * multi-bit scalar types and the empty string for a single bit. `Clock`/
    * `Reset` are 1-bit wires — the type distinction is a Scala-side check;
    * Verilog has no clock type (R13.1).
    *
    * Aggregates (`Bundle`/`Vec`) return `""` — the Verilog-2001 backend has no
    * packed structs or array ports, so the emitter expands an aggregate into
    * per-leaf scalars (R07.1) / a struct-of-arrays net (R14.1) at write time.
    * This is the "aggregates preserved in IR; resolution at emission" split. */
  def verilogRange: String = this match
    case Bool        => ""
    case UInt(w)     => if w == 1 then "" else s"[${w - 1}:0] "
    case SInt(w)     => if w == 1 then "" else s"[${w - 1}:0] "
    case Bits(w)     => if w == 1 then "" else s"[${w - 1}:0] "
    case GrayCode(w) => if w == 1 then "" else s"[${w - 1}:0] "
    case Clock       => ""
    case Reset(_)    => ""
    case Bundle(_)   => ""
    case Vec(_, _)   => ""

  /** The bit width of the type (R6). For aggregates this is the packed width:
    * `Bundle` = sum of field widths in declaration order (R07.2 — first field
    * in the MSBs); `Vec` = `n * elem.bitWidth`. The packing layout itself is
    * honoured by R07.2's packing and the emitter; `bitWidth` is just
    * the total. */
  def bitWidth: Int = this match
    case Bool        => 1
    case UInt(w)     => w
    case SInt(w)     => w
    case Bits(w)     => w
    case GrayCode(w) => w
    case Clock       => 1
    case Reset(_)    => 1
    case Bundle(fs)  => fs.iterator.map(_._2.bitWidth).sum
    case Vec(n, e)   => n * e.bitWidth

/** R13.1 — Reset polarity. Active-high (`if (rst)`) or active-low
  * (`if (!rst_n)`). A property of the clock domain, not of individual
  * registers (R13). */
enum ResetPolarity:
  case ActiveHigh, ActiveLow

package scalahdl.dsl

import scala.collection.mutable

import scalahdl.core.{Expr, Loc}
import scalahdl.diag.{Code, Diagnostic, Severity, ValidationFailed}

/** R40 — A register map.
  *
  * One declaration states where a register block sits and what its words mean.
  * Three consumers read it: the module that serves the registers, a C header for
  * firmware, and a SystemVerilog package for a testbench.
  *
  * ```scala
  * object UartRegs extends RegisterMap(prefix = "UART_", pkg = "uart_regs",
  *                                     base = 0x1000_0000, addressBits = 4):
  *
  *     object OpCodes extends CodedField(lo = 0, width = 2):
  *         val Send, Clear, Cancel = code
  *
  *     object Control extends Register(0):
  *         val Divider = bits(hi = 7, lo = 0)
  *         val Enable  = bit(8)
  *
  *     object Command extends Register(1):
  *         val Op    = add(OpCodes)
  *         val Index = bits(hi = 9, lo = 4)
  *
  *     registers(Control, Command)
  * ```
  *
  * **A map declares no hardware.** It names no signal and records no statement,
  * so it reaches no validation phase and no emitter, and the IR gains no node.
  * A module that reads a field through its handle emits the same Verilog as the
  * module that writes the bit pair by hand.
  *
  * **Every name comes from the declaration.** A field takes its `val`'s name
  * through R1's capture, a register and a coded field take their `object`'s
  * class name, and a code takes its `val`'s name through R24. The one string a
  * declaration may carry is a pinned C name, and it is written where the C
  * reader looks for it rather than derived from anything else.
  *
  * **A fault throws while the map initialises.** `registers(...)` is where a
  * register and the map meet, so every fault that needs both is decided there:
  * the word fit, the overlap, the address range and the coded field without
  * `add`. A wrong map therefore fails as its own body runs, not at a header a
  * design later reads.
  */

/** One field of a register word: its name, its lowest bit and its width. */
sealed trait Field:
    /** The name the declaration gave it. */
    def name: String
    /** The field's lowest bit, which is the shift a C header needs. */
    def lo: Int
    /** How many bits the field holds. */
    def width: Int

    /** The field's highest bit. */
    final def hi: Int = lo + width - 1

    /** The field's bits as a word mask: `width` ones shifted up by `lo`. */
    final def mask: BigInt = ((BigInt(1) << width) - 1) << lo

    /** The field's bits of `signal`, as a named window.
      *
      * A one-bit field reads as that bit, so a caller wanting a `Bool` gets one
      * without a cast. The window is constant, so R6.7's rule governs writing
      * it: a register is a target, a wire is not. */
    final def of(signal: DslSignal): DslSignal =
        if width == 1 then signal(lo) else signal(hi, lo)

/** A plain bit field: `val Divider = bits(hi = 7, lo = 0)`.
  *
  * Its name is R1's capture of the `val`, so a field cannot be anonymous.
  * Constructed by the factories below and by nothing else, which is why its
  * constructor is package-visible rather than private: an `inline` factory
  * cannot call a private constructor. */
final class RegisterField(
    val name: String, val lo: Int, val width: Int, val loc: Loc,
    val cName: String = ""
) extends Field

/** `bits(hi = 7, lo = 0)` — a field spanning several bits, named by its `val`.
  *
  * The field hands itself to the register it is declared in, so `fields` is
  * exactly what the register's body wrote, in the order it wrote it. */
inline def bits(hi: Int, lo: Int, cName: String = "")
              (using register: Register): RegisterField =
    register.add(new RegisterField(fieldName("register field"),
                                   lo, hi - lo + 1, Source.capture.loc, cName))

/** `bit(8)` — a one-bit field, named by its `val`. See `bits`. */
inline def bit(at: Int, cName: String = "")
              (using register: Register): RegisterField =
    register.add(new RegisterField(fieldName("register bit"), at, 1,
                                   Source.capture.loc, cName))

/** R1's capture, with the one refusal this surface makes: a field that no `val`
  * names cannot reach a header, because a header has nothing to call it.
  *
  * The spike measured which positions capture nothing — a `for … yield`, and a
  * `def` body with no `val` — so a generator that builds fields in a
  * comprehension meets this refusal instead of an invented name. */
inline def fieldName(what: String): String =
    Source.capture.name.getOrElse(
        throw ValidationFailed.one(Diagnostic(
            severity = Severity.Error,
            code = Code.REGMAP_FIELD_ANONYMOUS,
            summary = s"a $what is not bound to a `val`, so it has no name",
            primaryLoc = Source.capture.loc,
            fix = Some(s"bind the $what to a `val`, as in " +
                       "`val Divider = bits(hi = 7, lo = 0)`"))))

/** A field whose values are named codes: R24's `Encoding`, read as a field.
  *
  * It is an `Encoding`, so its members stand in every operand position —
  * `is(OpCodes.Send)`, `Command.Op.of(data) === OpCodes.Clear` — and a
  * diagnostic about it names the member:
  *
  * ```scala
  * object OpCodes extends CodedField(lo = 0, width = 2):
  *     val Send, Clear, Cancel = code
  *
  * object Command extends Register(1):
  *     val Op = add(OpCodes)
  * ```
  *
  * **Declared where R24 declares an encoding: as its own `object`.** A nested
  * `object` inside a register would be lazy, so its body would not run while the
  * register was built, and the register would carry a field that was never
  * created. An `object` in the map's body is built with the map, so its members
  * exist before any register names it.
  *
  * **Registered through `add` and never by itself.** It takes no register
  * parameter, so its only route into a register is `val Op = add(OpCodes)`. A
  * coded field that a register holds without `add` is refused when the map is
  * listed, which is what keeps a forgotten `add` from silently dropping names. */
abstract class CodedField(val lo: Int, override val width: Int)
    extends Encoding(width), Field:

    /** The C name each member was pinned with. A member absent here has none. */
    private val _cNames = mutable.Map.empty[EncodingValue, String]

    /** A member's pinned C name segment, or an empty string when it has none. */
    def pinnedCName(member: EncodingValue): String =
        _cNames.getOrElse(member, "")

    /** Where this field was declared, when a diagnostic needs it. `Encoding`
      * carries no `Loc`, so a coded field reports none. */
    private[dsl] def loc: Loc = Loc.NoLoc

    // `width` is the encoding's own as well: `Encoding(width)` pins it, so the
    // field's width and the width its members are emitted at cannot differ.

    /** `val Send, Clear, Cancel = code` — one member per code, counted from
      * zero. `inline` so R24's capture names each member's own `val`; a plain
      * `def` names itself and makes every member anonymous. */
    inline def code: EncodingValue = Value

    /** `val Send = code(1)` — a member with a pinned code. Counting resumes at
      * `code + 1`. An external document may fix the code, and the C name too.
      *
      * `inline` for the same reason as `code`: R24's capture names each member's
      * own `val`. A plain `def` here would name every pinned member after itself.
      */
    inline def code(at: Int, cName: String = ""): EncodingValue =
        pin(Value(at), cName)

    /** Record a member's pinned C name, and return the member.
      *
      * Outside the `inline` body on purpose: a local `val` inside it would
      * become the enclosing definition, and R24's capture would name every
      * pinned member after that `val` instead of after the member's own `val`.
      */
    private def pin(member: EncodingValue, cName: String): EncodingValue =
        if cName.nonEmpty then _cNames(member) = cName
        member

/** One register: its word offset in the block, and its fields.
  *
  * A register takes its name from its `object`'s class name, as R24's
  * `Encoding.name` does, so it needs no string argument.
  *
  * `words` is how many words the register occupies from `offset`. `count` and
  * `stride` declare a register that repeats: slot `k` starts at
  * `offset + k * stride`. `cName` pins the C name segment, for a register whose
  * name an external document fixes.
  *
  * **A register takes no map.** A map lists its registers, so a register that
  * asked the map for a `given` would start the map's body while its own was
  * still running. Code outside a map names a register first, and that order
  * then reads a register that is still null. A register therefore states no
  * fact that needs the map, and every check that needs both runs in
  * `registers(...)`. */
abstract class Register(val offset: Int,
                        val words: Int = 1,
                        val count: Int = 1,
                        val stride: Int = 1,
                        val cName: String = ""):

    require(words >= 1, s"register at word $offset must hold at least one word")
    require(count >= 1, s"register at word $offset must occur at least once")

    /** This register, for the field factories nested in its body to find. */
    given Register = this

    /** The `object`'s class name, which is the only name this declaration has. */
    val name: String =
        val fromClass =
            try getClass.getSimpleName.stripSuffix("$")
            catch case _: Throwable => ""
        if fromClass.nonEmpty && !fromClass.forall(_.isDigit) then fromClass
        else "Register"

    private val _fields = mutable.ListBuffer.empty[Placed]

    /** Declare a field of this register, and return it.
      *
      * A coded field is named by the `val` that holds it: `val Op = add(OpCodes)`
      * records it under `Op`. A plain field already carries the same name from
      * its own factory, so this is the one route for both.
      *
      * The field's word fit is decided when the map lists the register, because
      * only the map states how wide a word is. */
    inline def add[A <: Field](field: A): A =
        this.place(field, Source.capture.name.getOrElse(field.name))
        field

    private def place(field: Field, name: String): Unit =
        _fields += Placed(name, field)

    /** The word offsets this register occupies, in order. */
    def wordsUsed: List[Int] =
        if count == 1 then (offset until offset + words).toList
        else (0 until count).toList.flatMap { slot =>
            val start = offset + slot * stride
            (start until start + words).toList
        }

    /** The word offset of repetition `index`. Stands in an `is(...)` label. */
    def at(index: Int): Int = offset + index * stride

    /** The fields and their names, ordered by lowest bit.
      *
      * Not declaration order: a coded field may be declared in the map and only
      * named here as `val Op = add(OpCodes)`, so a register's body order is a
      * matter of taste rather than of layout. Sorting by `lo` reads as the word
      * does, and `lo` is unique because overlap is refused. */
    def fields: List[Placed] = _fields.toList.sortBy(placed => placed.field.lo)

/** A field and the name its register gives it.
  *
  * The two differ only for a coded field: the encoding carries its own class
  * name (`OpCodes`), and a header needs the name the register's `val` gave it
  * (`Op`). The field's own facts are reachable through this type as well, so a
  * reader never has to go through `placed.field` to ask where the field sits. */
final case class Placed(name: String, field: Field):
    def lo: Int = field.lo
    def hi: Int = field.hi
    def width: Int = field.width
    def mask: BigInt = field.mask
    def of(signal: DslSignal): DslSignal = field.of(signal)

    /** The C name segment this field's declaration pinned, or an empty string.
      * A coded field pins a name per code, not one for the field. */
    def cName: String = field match
        case plain: RegisterField => plain.cName
        case coded: CodedField    => ""

/** The location a field was declared at, for a diagnostic that needs one. */
extension (field: Field)
    private[dsl] def location: Loc = field match
        case plain: RegisterField => plain.loc
        case coded: CodedField    => coded.loc

/** A register stands where a constant is needed, as its word offset.
  *
  * `is(UartRegs.Command)` is `is(1)`, so a switch decodes a register bus with
  * the same declaration the header and the package come from. R6.1 supplies the
  * width from the selector, so a bare `Int` is what a case label wants. */
given Conversion[Register, Expr] = register =>
    Expr.Lit(BigInt(register.offset), None, None)

/** A register also stands where an `Int` is needed, for the same reason. */
given Conversion[Register, Int] = register => register.offset

/** A register block: a base address, a word width, and the registers in it. */
abstract class RegisterMap(
    val prefix: String,
    val pkg: String,
    val base: BigInt,
    val addressBits: Int,
    val wordBits: Int = 16
):

    require(prefix.nonEmpty, "a register map needs a C name prefix")
    require(pkg.nonEmpty, "a register map needs a SystemVerilog package name")
    require(wordBits % 8 == 0, "a map's word is a whole number of bytes")

    /** How many bytes one word of this map occupies on the bus.
      *
      * An address counts bytes and a register offset counts words, so this is
      * the relation between them: `address(offset)` is `base` plus `offset`
      * words.
      */
    final def bytesPerWord: Int = wordBits / 8

    private var _registers = List.empty[Register]

    /** The registers, in the order `registers(...)` listed them.
      *
      * An `object` is lazy, so this is also the record of which register bodies
      * have run: passing one to `registers(...)` forces it, and a register the
      * call does not name has no fields and reaches no header. */
    def registers: List[Register] = _registers

    /** The membership list, and the check that the block is coherent.
      *
      * The faults decided here need more than one declaration: an offset wider
      * than the address, two registers on one word, a field wider than a word,
      * two fields of one register on one bit, and a coded field a register holds
      * without `add`. A register states no map fact of its own, so this call is
      * where the register and the map meet. */
    def registers(listed: Register*): Unit =
        _registers = listed.toList
        checkOffsets(listed.toList)
        listed.foreach(checkWordFit)
        listed.foreach(checkFields)
        listed.foreach(checkCodedFieldsAdded)

    /** Refuse a field that does not fit this map's word.
      *
      * The fault points at the field's own declaration, which is where a reader
      * can widen it. */
    private def checkWordFit(register: Register): Unit =
        register.fields.foreach { placed =>
            if placed.hi >= wordBits then
                throw ValidationFailed.one(Diagnostic(
                    severity = Severity.Error,
                    code = Code.REGMAP_FIELD_OUT_OF_RANGE,
                    summary = s"field `${placed.name}` of register " +
                              s"`${register.name}` reaches bit ${placed.hi}, and a " +
                              s"word of `$pkg` is $wordBits bits",
                    primaryLoc = placed.field.location,
                    fix = Some(s"keep the field within bits ${wordBits - 1}..0")))
        }

    /** Refuse a coded field that a register holds without `add`.
      *
      * A `CodedField` declared in a register's body is a field the register
      * never took, so it would be in no render and report no error. Catching it
      * here makes a forgotten `add` a diagnostic rather than a silent absence.
      * Every declared member of type `CodedField` is compared against the coded
      * fields the register actually holds. */
    private def checkCodedFieldsAdded(register: Register): Unit =
        val added = register.fields.collect { case placed if placed.field.isInstanceOf[CodedField] =>
            placed.field.asInstanceOf[CodedField]
        }.toSet
        register.getClass.getDeclaredFields.foreach { declared =>
            if classOf[CodedField].isAssignableFrom(declared.getType) then
                declared.setAccessible(true)
                declared.get(register) match
                    case coded: CodedField if !added.contains(coded) =>
                        throw ValidationFailed.one(Diagnostic(
                            severity = Severity.Error,
                            code = Code.REGMAP_FIELD_UNTAKEN,
                            summary = s"register `${register.name}` holds `${coded.name}` " +
                                      "without `add`, so it would reach no header",
                            primaryLoc = Loc.NoLoc,
                            fix = Some(s"declare it in the map and write " +
                                       s"`val ${coded.name} = add(${coded.name})` here")))
                    case other => ()
        }

    /** The byte address of word `offset`: the base plus `offset` words. */
    def address(offset: Int): BigInt = base + BigInt(offset) * bytesPerWord

    /** Refuse an offset wider than the address, and two registers on one word.
      *
      * The unit is the word a register occupies, not its first offset: a
      * register that spans eight words holds eight of them, and a register that
      * repeats holds its repetitions apart. */
    private def checkOffsets(listed: List[Register]): Unit =
        val seen = mutable.LinkedHashMap.empty[Int, Register]
        listed.foreach { register =>
            val top = register.wordsUsed.max
            val needed = BigInt(top).bitLength
            if needed > addressBits then
                throw ValidationFailed.one(Diagnostic(
                    severity = Severity.Error,
                    code = Code.REGMAP_OFFSET_OUT_OF_RANGE,
                    summary = s"register `${register.name}` reaches word " +
                              s"$top, which needs $needed bits, and " +
                              s"`$pkg` has $addressBits address bits",
                    primaryLoc = Loc.NoLoc,
                    fix = Some(s"widen the map to $needed address bits, or move the " +
                               "register below it")))
            register.wordsUsed.foreach { word =>
                seen.get(word) match
                    case Some(first) =>
                        throw ValidationFailed.one(Diagnostic(
                            severity = Severity.Error,
                            code = Code.REGMAP_DUPLICATE_OFFSET,
                            summary = s"registers `${first.name}` and `${register.name}` " +
                                      s"both hold word $word",
                            primaryLoc = Loc.NoLoc,
                            fix = Some("give one of them its own word")))
                    case None => seen(word) = register
            }
        }

    private def checkFields(register: Register): Unit =
        val taken = mutable.LinkedHashMap.empty[Int, Placed]
        register.fields.foreach { placed =>
            (placed.field.lo to placed.field.hi).foreach { bit =>
                taken.get(bit) match
                    case Some(other) =>
                        throw ValidationFailed.one(Diagnostic(
                            severity = Severity.Error,
                            code = Code.REGMAP_FIELDS_OVERLAP,
                            summary = s"fields `${other.name}` and `${placed.name}` of " +
                                      s"register `${register.name}` both hold bit $bit",
                            primaryLoc = Loc.NoLoc,
                            fix = Some("give each field its own bits")))
                    case None => taken(bit) = placed
            }
        }

    // ---- The C header -------------------------------------------------------

    /** This map's `#define` lines, without a guard and without a sentinel.
      *
      * A design that owns several maps writes them into one file, so this is
      * the part such a file joins. [[cHeader]] is this text with a guard around
      * it.
      *
      * A C macro has no scope, so every name here starts with [[prefix]] and a
      * code's name carries its register and its field. A name the declaration
      * pinned is used as pinned, because it is already a C name. A name taken
      * from a `val` is converted to upper snake case.
      */
    def cDefines: String =
        val out = new StringBuilder
        registers.foreach { register =>
            val reg = prefix + segment(register.cName, register.name)
            out.append(define(reg, s"0x${address(register.offset).toString(16)}u"))
            register.fields.foreach { placed =>
                val own = segment(placed.cName, placed.name)
                placed.field match
                    case plain: RegisterField =>
                        if plain.width == 1 then
                            out.append(define(s"${reg}_$own",
                                              s"(1u << ${plain.lo})"))
                        else
                            out.append(define(s"${reg}_${own}_SHIFT",
                                              s"${plain.lo}u"))
                            out.append(define(s"${reg}_${own}_MASK",
                                              s"0x${plain.mask.toString(16)}u"))
                    case coded: CodedField =>
                        coded.values.foreach { member =>
                            out.append(define(
                                s"${reg}_${own}_${segment(coded.pinnedCName(member), member.name)}",
                                s"${member.code}u"))
                        }
            }
            out.append('\n')
        }
        out.toString

    /** This map's C header, for firmware.
      *
      * The guard stops a second include of this file. The sentinel stops a
      * second map of the same prefix: a repeated `#define` is at best a
      * warning, and the sentinel is an `#error`. */
    def cHeader: String =
        val guard = cName(pkg) + "_H"
        val out = new StringBuilder
        out.append(s"/* Generated from a RegisterMap. Do not edit. */\n")
        out.append(s"#ifndef $guard\n#define $guard\n\n")
        out.append(s"#ifdef ${prefix}REGS_DEFINED\n")
        out.append(s"#error \"a second register map uses the prefix $prefix\"\n")
        out.append(s"#endif\n")
        out.append(s"#define ${prefix}REGS_DEFINED 1\n\n")
        out.append(cDefines)
        out.append("#endif\n")
        out.toString

    /** `#define NAME  value`, in a 26-character name column. */
    private def define(name: String, value: String): String =
        f"#define $name%-26s $value%n"

    // ---- The SystemVerilog package ------------------------------------------

    /** This map's SystemVerilog package, for a testbench.
      *
      * A bench indexes a register by its offset and slices a field by its low
      * bit and its width, so this states those and no mask. A package is a real
      * scope, so these members need no prefix; inside it, each starts with its
      * register's name.
      *
      * **A pinned C name stops at the header.** This package names every member
      * from the declaration, because a bench reads a scope rather than a C
      * macro, and a second naming of the same field would be one fact stated
      * twice. */
    def systemVerilogPackage: String =
        val out = new StringBuilder
        out.append(s"// Generated from a RegisterMap. Do not edit.\n")
        out.append(s"package $pkg;\n")
        registers.foreach { register =>
            val reg = cName(register.name)
            out.append(f"    localparam logic [${addressBits - 1}:0] " +
                       f"${reg}_ADDRESS = $addressBits'h${register.offset}%x;\n")
            register.fields.foreach { placed =>
                placed.field match
                    case plain: RegisterField =>
                        out.append(f"    localparam int unsigned " +
                                   f"${reg}_${cName(placed.name)}_SHIFT = ${plain.lo};\n")
                        if plain.width > 1 then
                            out.append(f"    localparam int unsigned " +
                                       f"${reg}_${cName(placed.name)}_WIDTH = ${plain.width};\n")
                    case coded: CodedField =>
                        val width = math.max(1, coded.width)
                        coded.values.foreach { member =>
                            out.append(f"    localparam logic [${width - 1}:0] " +
                                       f"${reg}_${cName(placed.name)}_${cName(member.name)} = " +
                                       f"$width'd${member.code};\n")
                        }
            }
        }
        out.append("endpackage\n")
        out.toString

/** The C name segment of a declaration: the pinned name when it gave one, and
  * the upper snake case of its `val` name otherwise. */
private def segment(pinned: String, name: String): String =
    if pinned.nonEmpty then pinned else cName(name)

/** `Camel` to `UPPER_SNAKE`: `DigitsLow` becomes `DIGITS_LOW`, so a name that
  * reads in Scala reads the same way in a header. */
private def cName(name: String): String =
    val out = new StringBuilder
    name.foreach { ch =>
        if ch.isUpper && out.nonEmpty && out.last != '_' then out.append('_')
        out.append(ch.toUpper)
    }
    out.toString.toUpperCase

object RegisterMap:

    /** Write one `.h` and one `.sv` for every map in `maps`, into `dir`.
      *
      * This is the one place a design's maps are named together, so it is where
      * two maps of one `prefix` or one `pkg` are refused. The list is not a
      * separate register of maps: it is the argument this write needs anyway,
      * so a map it omits writes no file and fails at its first `#include`. */
    def write(dir: java.nio.file.Path, maps: Seq[RegisterMap]): Unit =
        checkDistinct(maps)
        maps.foreach { map =>
            java.nio.file.Files.writeString(dir.resolve(s"${map.pkg}.h"), map.cHeader)
            java.nio.file.Files.writeString(dir.resolve(s"${map.pkg}.sv"), map.systemVerilogPackage)
        }

    /** Refuse two maps that share a C prefix or a SystemVerilog package name. */
    private def checkDistinct(maps: Seq[RegisterMap]): Unit =
        val prefixes = mutable.LinkedHashMap.empty[String, RegisterMap]
        val packages = mutable.LinkedHashMap.empty[String, RegisterMap]
        maps.foreach { map =>
            prefixes.get(map.prefix).foreach { first =>
                throw ValidationFailed.one(Diagnostic(
                    severity = Severity.Error,
                    code = Code.REGMAP_NAME_CLASH,
                    summary = s"two register maps use the C prefix `${map.prefix}`",
                    primaryLoc = Loc.NoLoc,
                    fix = Some("give one map its own prefix")))
            }
            packages.get(map.pkg).foreach { first =>
                throw ValidationFailed.one(Diagnostic(
                    severity = Severity.Error,
                    code = Code.REGMAP_NAME_CLASH,
                    summary = s"two register maps use the package `${map.pkg}`",
                    primaryLoc = Loc.NoLoc,
                    fix = Some("give one map its own package name")))
            }
            prefixes(map.prefix) = map
            packages(map.pkg) = map
        }

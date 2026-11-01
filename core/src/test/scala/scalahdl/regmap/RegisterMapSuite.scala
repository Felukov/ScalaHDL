package scalahdl.regmap

import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.dsl.{*, given}

/** R40 — A register map's declaration surface.
  *
  * The claims under test are narrow. A map records names, offsets and bit
  * windows, and refuses a declaration that cannot be rendered. It declares no
  * hardware, so nothing here elaborates a module or emits Verilog.
  *
  * The evidence that a fault fires is the diagnostic code, never the prose.
  */
class RegisterMapSuite extends munit.FunSuite:

    // ---- A map with a plain field and a coded field ------------------------

    object UartRegs extends RegisterMap(prefix = "UART_", pkg = "uart_regs",
                                        base = 0x1000_0000, addressBits = 4):

        object OpCodes extends CodedField(lo = 0, width = 2):
            val Send, Clear, Cancel = code

        object Control extends Register(0):
            val Divider = bits(hi = 7, lo = 0)
            val Enable  = bit(8)

        object Command extends Register(1):
            val Op    = add(OpCodes)
            val Index = bits(hi = 9, lo = 4)

        object Status extends Register(2):
            val Busy = bit(0)

        registers(Control, Status, Command)

    test("a field takes its name from its `val`, and its window from its ends"):
        assertEquals(UartRegs.Control.fields.map(field => field.name),
                     List("Divider", "Enable"))
        val divider = UartRegs.Control.fields.head
        assertEquals((divider.lo, divider.width, divider.hi), (0, 8, 7))
        assertEquals(divider.mask, BigInt(0xff))

    test("a one-bit field masks to one bit, at its own position"):
        val enable = UartRegs.Control.fields(1)
        assertEquals((enable.lo, enable.width), (8, 1))
        assertEquals(enable.mask, BigInt(0x100))

    test("a register takes its name from its `object`, and holds its offset"):
        assertEquals(UartRegs.registers.map(register => register.name),
                     List("Control", "Status", "Command"))
        assertEquals(UartRegs.registers.map(register => register.offset),
                     List(0, 2, 1))

    test("the list keeps the order `registers(...)` wrote"):
        assertEquals(UartRegs.registers.map(register => register.name),
                     List("Control", "Status", "Command"),
                     "declaration order is Control, Command, Status; the list " +
                         "says otherwise")

    test("a register's address is the base plus its word offset in bytes"):
        // An address counts bytes, a register offset counts words, and one word
        // is `bytesPerWord` bytes.
        assertEquals(UartRegs.bytesPerWord, 2)
        assertEquals(UartRegs.address(0), BigInt(0x1000_0000))
        assertEquals(UartRegs.address(1), BigInt(0x1000_0002))
        assertEquals(UartRegs.address(8), BigInt(0x1000_0010))

    test("a word of 32 bits gives four bytes an address, with no extra argument"):
        object WideRegs extends RegisterMap(prefix = "W32_", pkg = "wide_regs",
                                             base = 0, addressBits = 4, wordBits = 32):
            object R extends Register(1):
                val F = bit(0)
            registers(R)
        assertEquals(WideRegs.bytesPerWord, 4)
        assertEquals(WideRegs.address(1), BigInt(4))

    test("a word that is not a whole number of bytes is refused"):
        intercept[IllegalArgumentException] {
            object Odds extends RegisterMap(prefix = "O_", pkg = "odds",
                                            base = 0, addressBits = 4, wordBits = 12)
            Odds.registers
        }

    test("a coded field is an R24 encoding, so its members are named codes"):
        assertEquals(UartRegs.OpCodes.name, "OpCodes")
        assertEquals(UartRegs.OpCodes.width, 2)
        assertEquals(UartRegs.OpCodes.values.map(member => member.name -> member.code),
                     List("Send" -> 0, "Clear" -> 1, "Cancel" -> 2))

    test("a code can pin its number, and counting resumes above it"):
        object SdOps extends CodedField(lo = 0, width = 4):
            val Send = code(1)
            val SendSwitch = code(2)
            val Clear, Cancel = code
        assertEquals(SdOps.values.map(member => member.name -> member.code),
                     List("Send" -> 1, "SendSwitch" -> 2, "Clear" -> 3,
                          "Cancel" -> 4))

    test("a coded field reaches a register only through `add`, and has a window"):
        val op = UartRegs.Command.fields.map(placed => placed.field)
            .collectFirst { case coded: CodedField => coded }
        assertEquals(op.map(coded => (coded.lo, coded.width)), Some((0, 2)))

    // ---- The five declaration faults ---------------------------------------

    /** A map's body runs when first forced, as a nested `object`'s does. Each
      * fault below is therefore declared inside a map and then forced, so the
      * exception comes from the declaration itself. */
    private def refused(body: => Any): List[Code] =
        intercept[ValidationFailed](body).diagnostics.map(_.code)

    test("a field above the word is refused"):
        val codes = refused {
            object Wide extends RegisterMap(prefix = "W_", pkg = "w", base = 0, addressBits = 2):
                object R extends Register(0):
                    val TooHigh = bits(hi = 16, lo = 8)
                registers(R)
            Wide.registers
        }
        assertEquals(codes, List(Code.REGMAP_FIELD_OUT_OF_RANGE))

    test("two fields of one register on one bit are refused"):
        val codes = refused {
            object Overlap extends RegisterMap(prefix = "O_", pkg = "o", base = 0, addressBits = 2):
                object R extends Register(0):
                    val Low  = bits(hi = 7, lo = 0)
                    val Next = bits(hi = 8, lo = 7)
                registers(R)
            Overlap.registers
        }
        assertEquals(codes, List(Code.REGMAP_FIELDS_OVERLAP))

    test("two registers at one offset are refused"):
        val codes = refused {
            object Twice extends RegisterMap(prefix = "T_", pkg = "t", base = 0, addressBits = 2):
                object A extends Register(1):
                    val F = bit(0)
                object B extends Register(1):
                    val F = bit(0)
                registers(A, B)
            Twice.registers
        }
        assertEquals(codes, List(Code.REGMAP_DUPLICATE_OFFSET))

    test("an offset wider than the address bits is refused"):
        val codes = refused {
            object Narrow extends RegisterMap(prefix = "N_", pkg = "n", base = 0, addressBits = 2):
                object Far extends Register(4):
                    val F = bit(0)
                registers(Far)
            Narrow.registers
        }
        assertEquals(codes, List(Code.REGMAP_OFFSET_OUT_OF_RANGE))

    test("a field no `val` names is refused"):
        val codes = refused {
            object Anonymous extends RegisterMap(prefix = "A_", pkg = "a", base = 0, addressBits = 2):
                object R extends Register(0):
                    val Generated = for i <- 0 until 2 yield bits(hi = i, lo = i)
                registers(R)
            Anonymous.registers
        }
        assertEquals(codes, List(Code.REGMAP_FIELD_ANONYMOUS))

    test("a coded field a register holds without `add` is refused"):
        val codes = refused {
            object Untaken extends RegisterMap(prefix = "U_", pkg = "u", base = 0, addressBits = 4):
                object OpCodes extends CodedField(lo = 0, width = 2):
                    val Send, Clear = code
                object R extends Register(0):
                    // Declared, held, never added: it would reach no render.
                    val Op = OpCodes
                registers(R)
            Untaken.registers
        }
        assertEquals(codes, List(Code.REGMAP_FIELD_UNTAKEN))

    test("a register stands where a case label needs a constant"):
        // `is(UartRegs.Command)` is `is(1)`, so a register bus decodes with the
        // same declaration the header and the package come from.
        assertEquals(UartRegs.Command.offset, 1)
        val asExpr: scalahdl.core.Expr = UartRegs.Command
        assertEquals(asExpr, scalahdl.core.Expr.Lit(BigInt(1), None, None))

    test("a map with no prefix or no package is refused"):
        intercept[IllegalArgumentException] {
            object NoPrefix extends RegisterMap(prefix = "", pkg = "p", base = 0, addressBits = 2)
            NoPrefix.registers
        }
        intercept[IllegalArgumentException] {
            object NoPkg extends RegisterMap(prefix = "P_", pkg = "", base = 0, addressBits = 2)
            NoPkg.registers
        }

    // ---- Order of initialisation --------------------------------------------

    /** A map whose first register is read before the map's own body runs.
      *
      * `Early` is touched here, outside the map, so the map is not built yet.
      * This is the order a serving module uses: it names a field's window before
      * anything reads the map. */
    object ReadFirst extends RegisterMap(prefix = "RF_", pkg = "read_first",
                                          base = 0, addressBits = 4):
        object Early extends Register(0):
            val F = bit(0)
        object Later extends Register(1):
            val F = bit(0)
        registers(Early, Later)

    test("a register read before the map is built carries its own facts"):
        // A register that asked the map for a `given` would start the map's body
        // here, while this register was still null.
        assertEquals(ReadFirst.Early.offset, 0)
        assertEquals(ReadFirst.Early.fields.map(placed => placed.name), List("F"))

    test("the map lists that register once its body runs"):
        assertEquals(ReadFirst.registers.map(register => register.name),
                     List("Early", "Later"))

    test("a field that does not fit the word is refused when the map lists it"):
        val codes = refused {
            object Wide extends RegisterMap(prefix = "WF_", pkg = "wf", base = 0,
                                            addressBits = 4, wordBits = 8):
                object R extends Register(0):
                    val F = bits(hi = 9, lo = 0)
                registers(R)
            Wide.registers
        }
        assertEquals(codes, List(Code.REGMAP_FIELD_OUT_OF_RANGE))

    // ---- The forcing point --------------------------------------------------

    var unlistedBodyRan = false

    /** A map that declares two registers and lists one of them. */
    object Partial extends RegisterMap(prefix = "P_", pkg = "p", base = 0, addressBits = 4):
        object Listed extends Register(0):
            val A = bit(0)
        object Unlisted extends Register(1):
            unlistedBodyRan = true
            val B = bit(0)
        registers(Listed)

    test("a register the list does not name is absent from the map"):
        assertEquals(Partial.registers.map(register => register.name), List("Listed"),
                     "the list is the map's contents, so a register outside it is " +
                         "in no header the map renders")
        assertEquals(unlistedBodyRan, false,
                     "and its body has run nowhere: reading `registers` forces no " +
                         "register that the list does not name")

    // ---- A register's extent ------------------------------------------------

    object ExtentRegs extends RegisterMap(prefix = "E_", pkg = "extent_regs",
                                           base = 0, addressBits = 6):
        object Resp extends Register(8, words = 8):
            val Low = bit(0)
        object Mailbox extends Register(24, words = 2, count = 4, stride = 2):
            val Write = bit(15)
        object Plain extends Register(0):
            val F = bit(0)
        registers(Plain, Resp, Mailbox)

    test("a register that spans words holds each of them"):
        assertEquals(ExtentRegs.Resp.wordsUsed, (8 until 16).toList)
        assertEquals(ExtentRegs.address(ExtentRegs.Resp.offset), BigInt(16))

    test("a register that repeats holds its repetitions apart"):
        assertEquals(ExtentRegs.Mailbox.wordsUsed, List(24, 25, 26, 27, 28, 29, 30, 31))
        assertEquals(ExtentRegs.Mailbox.at(0), 24)
        assertEquals(ExtentRegs.Mailbox.at(1), 26)
        assertEquals(ExtentRegs.Mailbox.at(3), 30)
        assertEquals(ExtentRegs.Mailbox.at(1), ExtentRegs.Mailbox.offset + 1 * 2)

    test("a register that overlaps another's word is refused, and the word is named"):
        val codes = refused {
            object OverlapWords extends RegisterMap(prefix = "OW_", pkg = "ow", base = 0,
                                                     addressBits = 6):
                object Wide extends Register(8, words = 8):
                    val F = bit(0)
                object Inside extends Register(12):
                    val F = bit(0)
                registers(Wide, Inside)
            OverlapWords.registers
        }
        assertEquals(codes, List(Code.REGMAP_DUPLICATE_OFFSET))

    test("a multi-word register reaching past the address bits is refused"):
        val codes = refused {
            object NarrowWide extends RegisterMap(prefix = "NW_", pkg = "nw", base = 0,
                                                   addressBits = 2):
                object Wide extends Register(2, words = 4):
                    val F = bit(0)
                registers(Wide)
            NarrowWide.registers
        }
        assertEquals(codes, List(Code.REGMAP_OFFSET_OUT_OF_RANGE))

    // ---- The handle ---------------------------------------------------------

    test("a field's handle is its window, and a one-bit field is that bit"):
        val index = UartRegs.Command.fields
            .collectFirst { case placed if placed.name == "Index" => placed }
        assertEquals(index.map(field => (field.lo, field.hi, field.width)), Some((4, 9, 6)))

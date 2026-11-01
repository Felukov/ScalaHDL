package scalahdl.regmap

import java.nio.file.{Files, Path}

import scala.sys.process.Process

import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.dsl.{*, given}

/** R40 — the two renders, and the call that writes them.
  *
  * Both texts come from one declaration, so a test that pins one of them is
  * the check that the declaration and the text cannot drift. The C header is
  * asserted byte for byte because a design compiles firmware against it; the
  * SystemVerilog package likewise, because a bench imports it.
  */
class RegisterRenderSuite extends munit.FunSuite:

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
        registers(Control, Command)

    /** A second map, for the clash test. */
    object SpiRegs extends RegisterMap(prefix = "SPI_", pkg = "spi_regs",
                                       base = 0x1000_0100, addressBits = 4):
        object Data extends Register(0):
            val Byte = bits(hi = 7, lo = 0)
        registers(Data)

    // ---- The C header -------------------------------------------------------

    test("the C header renders every name prefixed, behind a sentinel"):
        val expected =
            """/* Generated from a RegisterMap. Do not edit. */
              |#ifndef UART_REGS_H
              |#define UART_REGS_H
              |
              |#ifdef UART_REGS_DEFINED
              |#error "a second register map uses the prefix UART_"
              |#endif
              |#define UART_REGS_DEFINED 1
              |
              |#define UART_CONTROL               0x10000000u
              |#define UART_CONTROL_DIVIDER_SHIFT 0u
              |#define UART_CONTROL_DIVIDER_MASK  0xffu
              |#define UART_CONTROL_ENABLE        (1u << 8)
              |
              |#define UART_COMMAND               0x10000002u
              |#define UART_COMMAND_OP_SEND       0u
              |#define UART_COMMAND_OP_CLEAR      1u
              |#define UART_COMMAND_OP_CANCEL     2u
              |#define UART_COMMAND_INDEX_SHIFT   4u
              |#define UART_COMMAND_INDEX_MASK    0x3f0u
              |
              |#endif
              |""".stripMargin
        assertEquals(UartRegs.cHeader, expected)

    test("a header names a register at the base plus its word offset in bytes"):
        assert(UartRegs.cHeader.contains("#define UART_COMMAND               0x10000002u"),
               UartRegs.cHeader)

    test("a one-bit field is one shifted bit, and a wider one is shift plus mask"):
        val header = UartRegs.cHeader
        assert(header.contains("#define UART_CONTROL_ENABLE        (1u << 8)"), header)
        assert(header.contains("#define UART_CONTROL_DIVIDER_SHIFT 0u"), header)
        assert(header.contains("#define UART_CONTROL_DIVIDER_MASK  0xffu"), header)

    // ---- Pinned C names -----------------------------------------------------

    /** A map whose names an external document fixes: a code pinned to its own
      * number and C name, a field pinned to a name with no register in it, and a
      * register pinned to a two-word name. */
    object SdRegs extends RegisterMap(prefix = "SD_", pkg = "sd_regs",
                                       base = 0x1000, addressBits = 5):
        object OpCodes extends CodedField(lo = 0, width = 4):
            val Send = code(1, cName = "SEND")
            val Clear = code(3)
        object Go extends Register(3):
            val Op = add(OpCodes)
            val Index = bits(hi = 9, lo = 4, cName = "INDEX")
        object BlocksLow extends Register(17, cName = "BLOCKS_LOW"):
            val Whole = bits(hi = 15, lo = 0)
        registers(Go, BlocksLow)

    /** A header's `#define`s as name -> value, so a test states a name and a
      * value rather than the name column's padding. */
    private def defines(header: String): Map[String, String] =
        header.linesIterator.flatMap { line =>
            line.split("\\s+").toList match
                case "#define" :: name :: value :: rest => Some(name -> value)
                case other => None
        }.toMap

    test("a map with pinned names renders byte for byte"):
        // The two tests below ask for names by lookup, so a change that moved a
        // byte anywhere else in the same render would pass them. This pins the
        // whole text, which is what "golden-tested" means.
        val expected =
            """/* Generated from a RegisterMap. Do not edit. */
              |#ifndef SD_REGS_H
              |#define SD_REGS_H
              |
              |#ifdef SD_REGS_DEFINED
              |#error "a second register map uses the prefix SD_"
              |#endif
              |#define SD_REGS_DEFINED 1
              |
              |#define SD_GO                      0x1006u
              |#define SD_GO_OP_SEND              1u
              |#define SD_GO_OP_CLEAR             3u
              |#define SD_GO_INDEX_SHIFT          4u
              |#define SD_GO_INDEX_MASK           0x3f0u
              |
              |#define SD_BLOCKS_LOW              0x1022u
              |#define SD_BLOCKS_LOW_WHOLE_SHIFT  0u
              |#define SD_BLOCKS_LOW_WHOLE_MASK   0xffffu
              |
              |#endif
              |""".stripMargin
        assertEquals(SdRegs.cHeader, expected)

    test("a pinned name is used as pinned, and an unpinned one is upper snake case"):
        val rendered = defines(SdRegs.cHeader)
        assertEquals(rendered.get("SD_GO_OP_SEND"), Some("1u"))
        assertEquals(rendered.get("SD_GO_OP_CLEAR"), Some("3u"))
        assertEquals(rendered.get("SD_GO_INDEX_SHIFT"), Some("4u"))

    test("a pinned register keeps its own name, and its address is unchanged"):
        val rendered = defines(SdRegs.cHeader)
        assertEquals(rendered.get("SD_BLOCKS_LOW"), Some("0x1022u"))
        assertEquals(SdRegs.address(SdRegs.BlocksLow.offset), BigInt(0x1022))

    test("a pinned name does not respell the package member"):
        // A package is a scope, so a member needs no external name; the C name
        // is the C file's problem and stops at the header.
        assert(SdRegs.systemVerilogPackage.contains("GO_OP_SEND"),
               SdRegs.systemVerilogPackage)
        assertEquals(SdRegs.systemVerilogPackage.linesIterator
                         .count(line => line.contains("GO_OP_SEND")), 1,
                     "each member is declared once")

    // ---- The body, without the guard ----------------------------------------

    test("cDefines is cHeader without the guard, the sentinel and the end"):
        val header = UartRegs.cHeader
        val body = UartRegs.cDefines
        assert(!body.contains("#ifndef"), body)
        assert(!body.contains("REGS_DEFINED"), body)
        assert(!body.contains("#endif"), body)
        assert(header.contains(body), "the header holds the body whole")
        assert(body.startsWith("#define UART_CONTROL"), body)

    test("two maps' bodies join into one header without a name clash"):
        val joined = "#ifndef BOTH_H\n#define BOTH_H\n\n" +
                     UartRegs.cDefines + SpiRegs.cDefines + "#endif\n"
        val rendered = defines(joined)
        assert(rendered.contains("UART_CONTROL"), joined)
        assert(rendered.contains("SPI_DATA"), joined)
        assertEquals(joined.linesIterator.count(line => line.contains("#ifndef")), 1,
                     "one guard, and neither map brought its own")
        assertEquals(joined.linesIterator.count(line => line.contains("#endif")), 1)

    // ---- The SystemVerilog package -----------------------------------------

    test("the package renders a word offset, a field's shift and width, and a code"):
        val expected =
            """// Generated from a RegisterMap. Do not edit.
              |package uart_regs;
              |    localparam logic [3:0] CONTROL_ADDRESS = 4'h0;
              |    localparam int unsigned CONTROL_DIVIDER_SHIFT = 0;
              |    localparam int unsigned CONTROL_DIVIDER_WIDTH = 8;
              |    localparam int unsigned CONTROL_ENABLE_SHIFT = 8;
              |    localparam logic [3:0] COMMAND_ADDRESS = 4'h1;
              |    localparam logic [1:0] COMMAND_OP_SEND = 2'd0;
              |    localparam logic [1:0] COMMAND_OP_CLEAR = 2'd1;
              |    localparam logic [1:0] COMMAND_OP_CANCEL = 2'd2;
              |    localparam int unsigned COMMAND_INDEX_SHIFT = 4;
              |    localparam int unsigned COMMAND_INDEX_WIDTH = 6;
              |endpackage
              |""".stripMargin
        assertEquals(UartRegs.systemVerilogPackage, expected)

    test("a package states no mask, because a bench slices"):
        assert(!UartRegs.systemVerilogPackage.contains("_MASK"))

    test("both renders order a register's members by their low bit"):
        // `Op` is a nested `object`, so its body runs only when forced. A render
        // that read the register's fields without forcing it would omit the
        // codes, and the header would silently lose three names.
        assert(UartRegs.cHeader.contains("UART_COMMAND_OP_SEND"))
        val opAt = UartRegs.cHeader.indexOf("UART_COMMAND_OP_SEND")
        val indexAt = UartRegs.cHeader.indexOf("UART_COMMAND_INDEX_SHIFT")
        assert(opAt < indexAt, "the codes are at bit 0, so they come before the index")

    // ---- The write call -----------------------------------------------------

    test("one call writes an `.h` and an `.sv` for every map, and refuses a clash"):
        val dir = Files.createTempDirectory("regmap")
        try
            scalahdl.dsl.RegisterMap.write(dir, Seq(UartRegs, SpiRegs))
            assert(Files.exists(dir.resolve("uart_regs.h")))
            assert(Files.exists(dir.resolve("uart_regs.sv")))
            assert(Files.exists(dir.resolve("spi_regs.h")))
            assert(Files.exists(dir.resolve("spi_regs.sv")))
            assertEquals(Files.readString(dir.resolve("uart_regs.h")), UartRegs.cHeader)
        finally deleteAll(dir)

    test("two maps of one prefix are refused, naming the code"):
        val dir = Files.createTempDirectory("regmap-clash")
        try
            object Clash extends RegisterMap(prefix = "UART_", pkg = "other", base = 0, addressBits = 4):
                object R extends Register(0):
                    val F = bit(0)
                registers(R)
            val failure = intercept[ValidationFailed] {
                scalahdl.dsl.RegisterMap.write(dir, Seq(UartRegs, Clash))
            }
            assertEquals(failure.diagnostics.map(_.code), List(Code.REGMAP_NAME_CLASH))
            assert(failure.diagnostics.head.summary.contains("UART_"),
                   failure.diagnostics.head.summary)
        finally deleteAll(dir)

    test("the sentinel stops a C compile when two headers share a prefix"):
        // The oracle is the compiler, not a search of the text: a test that
        // greps for `#error` passes on a header whose sentinel sits after the
        // defines, which is the fault this sentinel exists to prevent.
        val cc = Seq("cc", "gcc", "clang").find { name =>
            scala.util.Try(Process(Seq(name, "--version")).! == 0).getOrElse(false)
        }
        cc match
            case None =>
                assert(false, "no C compiler on PATH: cc, gcc or clang is needed " +
                              "to prove the sentinel stops a build")
            case Some(compiler) =>
                val dir = Files.createTempDirectory("regmap-c")
                try
                    val first = dir.resolve("first.h")
                    val second = dir.resolve("second.h")
                    val main = dir.resolve("main.c")
                    Files.writeString(first, UartRegs.cHeader)
                    // A second map of the same prefix, rendered by the library
                    // but written by a separate run: the generator cannot see
                    // it, so only the sentinel can. Its `#ifndef` guard has a
                    // different name, so it really is included below.
                    object UartAgain extends RegisterMap(prefix = "UART_", pkg = "uart_again",
                                                         base = 0x2000_0000, addressBits = 4):
                        object Other extends Register(0):
                            val F = bit(0)
                        registers(Other)
                    Files.writeString(second, UartAgain.cHeader)
                    Files.writeString(main,
                        "#include \"first.h\"\n#include \"second.h\"\nint main(void) { return 0; }\n")
                    val exit = Process(Seq(compiler, "-I", dir.toString, "-c",
                                           main.toString, "-o",
                                           dir.resolve("main.o").toString)).!
                    assert(exit != 0,
                           s"$compiler accepted two headers of one prefix (exit $exit)")
                finally deleteAll(dir)

    test("the sentinel lets a C compile through when the prefixes differ"):
        val cc = Seq("cc", "gcc", "clang").find { name =>
            scala.util.Try(Process(Seq(name, "--version")).! == 0).getOrElse(false)
        }
        val compiler = cc.getOrElse(throw new AssertionError("no C compiler on PATH"))
        val dir = Files.createTempDirectory("regmap-c-ok")
        try
            Files.writeString(dir.resolve("uart.h"), UartRegs.cHeader)
            Files.writeString(dir.resolve("spi.h"), SpiRegs.cHeader)
            val main = dir.resolve("main.c")
            Files.writeString(main,
                "#include \"uart.h\"\n#include \"spi.h\"\n" +
                "int main(void) { return (int)(UART_CONTROL + SPI_DATA); }\n")
            val exit = Process(Seq(compiler, "-I", dir.toString, "-c", main.toString,
                                   "-o", dir.resolve("main.o").toString)).!
            assertEquals(exit, 0, s"$compiler refused two headers of different prefixes")
        finally deleteAll(dir)

    test("two maps of one package are refused"):
        val dir = Files.createTempDirectory("regmap-pkg")
        try
            object Clash extends RegisterMap(prefix = "OTHER_", pkg = "uart_regs", base = 0, addressBits = 4):
                object R extends Register(0):
                    val F = bit(0)
                registers(R)
            val failure = intercept[ValidationFailed] {
                scalahdl.dsl.RegisterMap.write(dir, Seq(UartRegs, Clash))
            }
            assertEquals(failure.diagnostics.map(_.code), List(Code.REGMAP_NAME_CLASH))
        finally deleteAll(dir)

    private def deleteAll(dir: Path): Unit =
        import scala.jdk.CollectionConverters.*
        Files.walk(dir).iterator.asScala.toList.reverse.foreach(path => Files.deleteIfExists(path))

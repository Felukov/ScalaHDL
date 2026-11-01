# R40 — A register map

**Status:** **closed** — implemented and used by a design. See "What closed it".
**Spec:** §5 Types, R24 (named encodings). **A new spec rule, R40, is
required.** R40 has the shape R24 has: one declaration states the facts, and
the numbers derive from it.
**Depends on:** R1 (name capture), R6.1 (literal width from context), R21.4
(`resize`), R22 (`is` takes an elaboration-time constant), R24 (an encoding is a
surface over `UInt`)
**Unblocks:** one declaration of a register block, a C header and a
SystemVerilog package rendered from it, and typed field handles in the modules
that serve it

## Goal

**One declaration states what a register block holds: a base address, a word
offset per register, and a bit field per register. Three consumers read it.**

```
  object UartRegs extends RegisterMap(...)
        │
        ├── the modules        is(UartRegs.Command), UartRegs.Command.Index.of(data)
        ├── the C header        #define UART_CONTROL  0x10000000u
        └── the SV package      package uart_regs; localparam CONTROL_ADDRESS = ...
```

The purpose is one place for a number that three files need. Today a register's
word offset appears in its serving module, again in a hand-written C header, and
again in a SystemVerilog include that copies the C header by hand.

## Why now

A design that uses this library must agree with itself about where its registers
are. Three files state that agreement, and only one of them is the design:

```
   a register's offset    a val in the module that serves the register
        ↓  written again as
   a line in a header     the C the firmware is compiled against
        ↓  written again as
   a localparam           the bench declares it, because C and SystemVerilog
                          share no header
```

A bench cannot include a C header, so the third copy is not avoidable by care.
It is avoidable only by rendering both texts from one declaration.

A register that moves in the module compiles, emits, and gives firmware a stale
address. Nothing in the build compares the three copies.

R24 answered the same fault for a set of named constants. R40 answers it for a
set of named words and fields.

**This is a library rule, not one design's need.** The fault is in the shape of
what the library offers: it names a port and a signal, and it has no way to say
where a register sits. A design can express the fact only by writing the number
down three times.

## The declaration

A register block is an object with a base, a word width and an address width.
A register is a nested object holding its offset and its fields.

```scala
object UartRegs extends RegisterMap(prefix = "UART_", pkg = "uart_regs",
                                    base = 0x1000_0000, addressBits = 4):

    /** The command's codes, declared where R24 declares an encoding. */
    object OpCodes extends CodedField(lo = 0, width = 2):
        val Send, Clear, Cancel = code

    /** The divider is the whole low byte. */
    object Control extends Register(0):
        val Divider = bits(hi = 7, lo = 0)
        val Enable  = bit(8)

    object Command extends Register(1):
        val Op    = add(OpCodes)
        val Index = bits(hi = 9, lo = 4)

    registers(Control, Status, Command)
```

`bits(hi, lo)` takes its two ends in the order `sig(hi, lo)` already takes them.
`bit(n)` is a one-bit field at `n`: it reports `lo`, `width` and `mask` like any
other field, and its `of(signal)` answers that bit alone.

`CodedField(lo, width)` is an R24 encoding that is also a field. It is declared
as an `object` in the map, for two reasons. Its members must be reachable as
`OpCodes.Send`, and only an `object`'s members are; and a nested `object` inside
a register would be lazy, so its body would not run while the register was built.

`val Op = add(OpCodes)` is how a register takes the field. The `val`'s name is the
field's name, so this register emits `UART_COMMAND_OP_SEND` whatever the codes
object is called. A coded field that a register does not `add` is refused when the
map is listed, so a forgotten `add` is an error rather than an absent name.

`Register(1)` is the word offset inside the block. An address counts bytes and a
register offset counts words, so one word occupies `wordBits / 8` bytes. The
block's address for the register is `base + offset * (wordBits / 8)`. A word of
16 bits gives two bytes an address; a word of 32 bits gives four.

`registers(...)` is the membership list. It is also the forcing point: passing an
object as an argument runs that object's body, which is what adds the fields to
the header. A register that no `registers(...)` call names does not reach the
header.

A coded field that a register holds without `add` is refused here
(`REGMAP_FIELD_UNTAKEN`), because it would otherwise be in no render and report
nothing. That is what makes a forgotten `add` an error rather than a silent
absence.

## Name collisions

Neither output language gives a map a scope of its own, so the declaration must
supply the names that make the outputs distinct.

```
  name                          scope of a collision            closed by
  ----------------------------  ------------------------------  ----------------
  C macro #define               the whole translation unit      prefix, always
  SV localparam in a package    one package                     the package
  package name                  the compilation unit            pkg, checked
```

**`prefix` is a required argument with no default.** A C macro has no scope, so
the prefix is the only thing that makes a name unique, and a default would let
two maps collide without a word.

**Every `#define` starts with the prefix, a code's included.** A code named
`Send` in two registers stays two names because the register's name is in the
path: `UART_COMMAND_OP_SEND`.

**The SystemVerilog render is a package**, `pkg`. A package is a real scope, so
two maps may hold one member name, and a bench says `uart_regs::CONTROL_ADDRESS`
or imports one package at a time. Inside the package a member starts with the
register's upper-case name. A code's member carries the field's name too, so two
registers of one map never collide either.

**Two maps of one design are compared where they are already named together.**

```scala
    RegisterMap.write(dir, Seq(UartRegs, SpiRegs))
```

That call writes one `.h` and one `.sv` per map. It refuses two maps that share
a `prefix` or a `pkg`. The list is not a separate register of maps: it is the
argument the write already needs for its second file. A map it omits writes
nothing, so it fails at its first `#include`. A global registry of maps would
catch more and would break R3's ban on ambient state, so it is refused.

## What the spike measured

The surface rests on four facts about Scala and about R1's name capture. Each
was measured before the surface was written, and each measurement is a test in
`core/src/test/scala/scalahdl/regmap/RegisterMapSuite.scala`, where the cases
that first measured them still run.

**Where a name is captured, and where it is not.**

| where the factory is called | captured name |
|---|---|
| `val x = bits(…)`, in any scope | `x` |
| a `val` in a class body, which a register is | that `val`'s name |
| a `val` in an `object` body, which a register is | that `val`'s name |
| through an `inline def` helper | the caller's `val` |
| a loop body that binds a `val` | that `val`'s name |
| a `for … yield bits(…)` | none — refused |
| a `def` body with no `val` | none — refused |

So a field needs no string argument wherever a design binds it to a `val`, and
a generator that builds fields in a comprehension gets no names. The surface
refuses those rather than inventing one, which is why
`REGMAP_FIELD_ANONYMOUS` exists.

**A register needs no string either.** The `object`'s class name is the
register's name, through the same `getClass.getSimpleName.stripSuffix("$")`
that R24's `Encoding.name` uses.

**A coded field must publish its members through an `inline def`.** Plain
`def code = Value` captures the *def's* own name and makes every member
anonymous; `inline def code` expands the capture at each member's `val`. This is
the reason R24's `Value` is `inline`, now measured for R40's use.

**The membership list is the only force, and that is provable.** An `object`
that no list names was never forced in the spike: its body did not run and its
fields were empty. Naming it in the list ran the body. A register left out of
the list therefore cannot reach a header, which is what makes the list a check
rather than a convention.

## The typed handles

A field's facts are its low bit and its width. The module reads them, never
repeating them:

```scala
   UartRegs.Command.Index.of(bus.writeData)   // the part-select, width 6
   UartRegs.Command.Index.lo                  // 4
   UartRegs.Command.Index.width               // 6
```

Before this rule the same module writes `bus.writeData(9, 4)` and the header
writes `UART_COMMAND_INDEX_SHIFT 4u`. Both numbers are the field's, written twice.

A coded field is an encoding (R24), so its members already stand in every
operand position. `is(UartRegs.OpCodes.Send)` and
`UartRegs.Command.Op.of(data)` need no new mechanism.

A register stands where a case label needs a constant, through one conversion:
`is(UartRegs.Command)` is `is(1)`. The conversion is declared once, beside the
register factories.

## The header

`RegisterMap` renders the C text from the same objects. For each register it
writes the address, then the register's fields:

```
#define UART_CONTROL               0x10000000u   the address
#define UART_CONTROL_ENABLE        (1u << 8)     a bit field
#define UART_COMMAND_INDEX_SHIFT   4u            a field's low bit
#define UART_COMMAND_OP_SEND       0u            a member of a coded field
```

The rendering states its own rules, and each rule is one sentence:

- the file opens by refusing a second map of its prefix;
- the name is `prefix`, then `UPPER(register)`, then `_` and `UPPER(field)`;
- a bit field is `(1u << n)`;
- a coded field writes one define per member: register, field, member's name;
- a plain field wider than one bit writes `_SHIFT` and `_MASK`;
- the address is `base + 2 * offset`, as 8 hex digits and a `u` suffix;
- the name column is 26 characters wide, as the current header has it.

### The prefix sentinel

C requires a diagnostic when a redefinition differs and permits one when it does
not, so a repeated `#define` is a warning at best and silent at worst. The header
asks for the failure itself:

```c
#ifndef UART_REGS_H
#define UART_REGS_H

#ifdef UART_REGS_DEFINED
#error "a second register map uses the prefix UART_"
#endif
#define UART_REGS_DEFINED 1
```

`#error` stops a build under every compiler, with no flag to set. One sentinel
suffices because every name carries the prefix: two headers can collide only by
sharing a prefix, so the prefix is the whole collision surface.

It catches the one case the generator cannot see — two maps written by two
separate runs — and it also catches a hand-written header that copies the names.

### The tests

Two, and they check different things.

- **The golden test** asserts the rendered text byte for byte. A difference is a
  formatting decision that a reader confirms, not a drift that a build discovers.
- **The clash test** writes two maps that share a prefix into two headers, passes
  both to a C compiler, and requires the compile to fail with the sentinel's
  message. A test that only greps the text for `#error` would pass on a header
  whose sentinel sits after the defines, so the compiler is the oracle.

## The SystemVerilog package

A bench indexes a word by its offset and slices a field by its low bit and
width, so the package states those and no mask:

```systemverilog
package uart_regs;
    localparam logic [3:0] CONTROL_ADDRESS = 4'h0;
    localparam int unsigned CONTROL_DIVIDER_SHIFT = 0;
    localparam int unsigned CONTROL_DIVIDER_WIDTH = 8;
    localparam logic [1:0] COMMAND_OP_SEND = 2'd0;
endpackage
```

The rules:

- the file holds one `package`, named `pkg`;
- a register member is `UPPER(register) + "_ADDRESS"`, typed at the map's
  address width, holding the word offset in hex;
- a field member is `UPPER(register) + "_" + UPPER(field)`, with `_SHIFT` and
  `_WIDTH` variants, both `int unsigned`;
- a code member is `UPPER(register) + "_" + UPPER(field) + "_" + UPPER(code)`,
  typed at the field's width, in decimal.

A bench then reads a field as
`got[uart_regs::CONTROL_DIVIDER_SHIFT +: uart_regs::CONTROL_DIVIDER_WIDTH]`,
which is the sliced form R34 emits, or imports one package and writes the names
plain.

The bench's tasks, its stimulus and its checks stay hand-written. Only the
numbers move. The package is a file the bench imports beside its own text, so no
testbench logic is generated.

## What R40 does not do

**The register decode stays hand-written.** Each register's behaviour differs: a
read-only status word, a write-only command with its own inner `switch`, a word
that reads in eight slices. A decode helper would need a body per register, and
that is a separate construct with its own requirement.

**No hardware comes from a map.** A map declares no signal, no register in the
R13 sense and no statement. It emits no Verilog. R12's promise is unchanged and
the IR gains no node.

**A map is not a bus definition.** It states where a word sits and what its bits
mean. It says nothing about a read's timing or a write's effect.

## Errors

Each error is its own R19 code. The first five fire while the map object
initialises, because that is the first moment the whole block exists. The sixth
fires when the maps of one design are written together.

| code | the fault |
|---|---|
| `REGMAP_OFFSET_OUT_OF_RANGE` | a register's offset needs more bits than `addressBits` |
| `REGMAP_DUPLICATE_OFFSET` | two named registers hold one offset; both are named |
| `REGMAP_FIELD_OUT_OF_RANGE` | a field's high bit is above the word's width |
| `REGMAP_FIELDS_OVERLAP` | two fields of one register share a bit; both are named |
| `REGMAP_FIELD_ANONYMOUS` | a field or a code is not bound to a `val`, so it has no name |
| `REGMAP_NAME_CLASH` | two maps of one design share a `prefix` or a `pkg`; both are named |
| `REGMAP_FIELD_UNTAKEN` | a register holds a coded field it never took through `add` |

## Exit criteria

- [x] A map declares its base, word width and address width; a register declares
      its offset; a field declares its two ends; a coded field declares its
      members.
- [x] A map declares a C name `prefix` and a SystemVerilog package name `pkg`,
      both required and neither defaulted, because neither language gives a map
      a scope the library can rely on.
- [x] Every `#define` starts with `prefix`, a code's included; every package
      member starts with its register's upper-case name, a code's included.
- [x] The call that writes a design's maps refuses two maps sharing a `prefix`
      or a `pkg` (`REGMAP_NAME_CLASH`), naming both.
- [x] Every register, field and code takes its name from its `val` (R1), and an
      unnamed one is refused.
- [x] A coded field is an `object` in the map, and its register takes it through
      `add`; the name it takes is the register's `val`, so the header names the
      field as that register named it.
- [x] `registers(...)` is the membership list, and either render runs the body of
      every register it names.
- [x] A field gives `of(signal)`, `lo`, `hi`, `width` and `mask`; a coded field
      gives its R24 members and its R24 diagnostics.
- [x] A register stands in an `is(...)` label as its word offset.
- [x] The C header renders addresses, bit fields, coded fields and wide fields by
      the seven rules above, and opens with the prefix sentinel.
- [x] The SystemVerilog render is one `package`, rendering a word offset, a
      field's shift and width, and a code, each typed and named as its rules
      state.
- [x] Both renders are golden-tested from one small map, so a change that moves
      one byte is visible in one place.
- [x] The clash test compiles two headers of one prefix and requires the compile
      to fail on the sentinel, so the check is the compiler's, not a grep.
- [x] Each of the six error codes has a test that names it.
- [x] No emitted Verilog changes: a module that reads a field through a handle
      emits the same bytes as the module that wrote the bit pair by hand.

## What closed it

The library landed in five commits, and a second repository then used it.

```
   c9a6f18  the five changes a real design's maps needed
   423a415  a register takes no map, so a module may name one first
   d7bf574  the fault the move left behind, and the field-fit location
```

**The five changes, each of which a real map needed:**

1. `CodedField.code(at)` pins a member's number. The SD host's operations are
   1, 2, 3, 4, and the counting form gives 0 through 3.
2. `bytesPerWord` derives the byte count from `wordBits`. An address counts
   bytes and an offset counts words, and the map states the width, so the
   relation is written once instead of as a literal `2`.
3. `Register(words, count, stride)` states a register that spans words or
   repeats. `wordsUsed` and `at(index)` report them, and the clash check
   compares occupied words.
4. `cName` on a register, a field and a code pins a C name, for a name an
   external document fixes. It defaults to the captured `val` name, and it
   reaches the header only: a package names its members from the declaration.
5. `cDefines` renders a map's `#define` lines without a guard, and `cHeader`
   wraps that text. A design that owns four maps writes them into one file, as
   `CompanionLayout.header` does.

**The one promise that moved.** A field that does not fit its word is refused
when `registers(...)` lists its register, not at the field's own line, because
only the map states how wide a word is. The fault still carries the field's own
location, so the reader is sent to the declaration that needs the change.

**What the second repository proved.** Four maps state the companion's window,
the header is their render, 21 field reads in four serving modules go through
their handles, and the SD bench imports the generated package instead of
restating the offsets. Twelve macro names gained their register's name because
a render joins prefix, register and field; no macro's value changed, and the
emitted Verilog is unchanged module for module.

## Spec follow-up

The spec gains one rule, R40, in §5 Types after R24. It states the construct,
the three consumers, the two renders and their rules, the fact that no IR node
and no signal come from a map, and the five errors. It records that the decode
is out of scope and that a bench's own logic is never generated.

## Open decisions

1. **The field spelling.** `bits(hi, lo)` matches `sig(hi, lo)`; `bits(lo,
   width)` matches the shift the header needs. This task takes `bits(hi, lo)`
   and derives the rest. The spike used this spelling and it captured names
   through the `inline def` unchanged.
2. **The membership list.** A `registers(...)` call is a second place a register
   is named. Its cost is that a register left out of the list is absent from the
   header. Its benefit is that the compiler checks every name in the list, and
   that the call is the only reliable forcing point for an `object` body. The
   spike measured the forcing: an object no list names never runs its body, so
   its fields are empty and it cannot reach a header.
3. **The renders' home.** This task renders both texts in `core`. A design's own
   extra defines and localparams stay in the design, and this task does not
   invent a way to merge them.
4. **The render's file name.** The renderer returns the text; the caller names
   the file. Nothing in the text needs to know its own file name, and a package
   needs no guard because a duplicate package is an error the tool reports.

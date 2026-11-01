# Scala 3 HDL — Requirements Specification

**Status:** Draft 1 — front end, IR, and emission settled. The contract is
complete; what a library implements against it is not stated here.
**Target:** Verilog-2001 (primary), SystemVerilog (optional backend).
**Host language:** Scala 3.
**Purpose:** Educational and research use.

---

**Contents:** [0 Design philosophy](#0-design-philosophy) · [1 Naming and structure](#1-naming-and-structure) · [2 Elaboration](#2-elaboration) · [3 Clocking and reset](#3-clocking-and-reset) · [4 Clock domain crossing](#4-clock-domain-crossing) · [5 Types](#5-types) · [6 Ports and logic](#6-ports-and-logic) · [7 Memory](#7-memory) · [8 Blackboxes](#8-blackboxes) · [9 Intermediate representation](#9-intermediate-representation) · [10 Diagnostics](#10-diagnostics) · [11 Emission](#11-emission) · [12 Non-goals](#12-non-goals) · [A async FIFO](#appendix-a--validation-exercise-async-fifo) · [B id index](#appendix-b--requirement-index) · [C The naming bet](#appendix-c--the-naming-bet) · [D PicoRV32 datapath](#appendix-d--validation-exercise-picorv32-datapath) · [E Glossary](#appendix-e--glossary)

## 0. Design philosophy

The library exists because generated RTL should be *readable and predictable*. SpinalHDL comes
closest to this today; Chisel does not, largely because it materialises unnamed intermediate
signals (`_T_412`) and renumbers modules on unrelated edits.

Three properties are treated as non-negotiable, and every requirement below is downstream of them:

1. **Correspondence.** A reader can map emitted Verilog back to the Scala that produced it,
   line by line, without consulting the emitter source.
2. **Locality.** A change in one part of a design does not rename, renumber, or reorder anything
   elsewhere.
3. **Explicitness.** Structure appears in the output because the user asked for it. Nothing is
   inferred, defaulted, or silently introduced.

Where convenience conflicts with these, the properties win. Where a rule is needed, it is a
*rule* — same input shape always produces the same output shape — never a heuristic.

---

## 1. Naming and structure

### Name preservation (R1)

Every named Scala `val` that becomes a signal appears in the emitted Verilog under that name,
unmangled. Collisions resolve by a documented, stable rule.

**Mechanism:** a Scala 3 `inline def` factory inspecting `Symbol.spliceOwner`. For
`val cnt = UInt(8)` the splice owner is the symbol `cnt`, so the name is known at compile
time. This works for locals inside methods and loop bodies, which field reflection cannot reach,
and requires **no compiler plugin** — a deliberate choice, since a plugin pins the project to
compiler internals and is a poor tax on a library meant to be read and forked.

Where the splice owner is not a `val`, the value is definitively anonymous rather than guessed at.

Source location (file, line, column) is captured by the same macro at zero runtime cost, and feeds
R19.

### A name is not a keyword (R1.1)

A name R1 carries into the Verilog unmangled must be one a Verilog reader takes for a name. Every
name the emitted file declares — each leaf of a signal or a port, an instance, a memory, and the
module's own name — is checked against the **248 reserved keywords of IEEE 1800-2017, Annex B**,
and a match is `NAME_RESERVED` at the declaration:

```
error[NAME_RESERVED]: `byte` is a SystemVerilog keyword and cannot name a signal (R1.1)
```

- **The SystemVerilog table, not Verilog-2005's.** The emitted files are read as SystemVerilog —
  Verilator's default — and the smaller table would pass `byte`, which is how the rule was found:
  a register of that name reached the Verilog and the first word of the defect was a parse error.
  The larger table holds every Verilog-2005 keyword besides.
- **Leaves, not roots.** A bundle `accept` with a field `on` emits `accept_on`, a keyword neither
  part is. The names the emitter composes (`inst_port`, `name_0`) are built from checked names and
  are not checked again.
- **Refused, not escaped.** Verilog can escape any identifier (`\byte `), and R1 promises the name
  comes out as it went in; an escaped name is one every testbench and hierarchical reference has
  to spell escaped.
- **Case matters**, as it does in Verilog: `Byte` is a name.
- **A tool's own reserved words are not the language's.** Verilator warns on C++ words (`abort`)
  and renames them internally; they are legal Verilog, and a lint gate is where they belong.

### No inferred structure (R2)

> A hardware value is either a user-named signal or an anonymous expression tree. Anonymous trees
> never receive a declaration — they inline at every use site. **Wire count in the emitted Verilog
> equals signal count in the Scala.**

Consequences, accepted deliberately:

- `val t = a + b` **is** a declaration. It emits `wire [8:0] t = a + b;`. Binding to a `val` and
  declaring a wire are the same act, so the user controls sharing explicitly.
- A `def` returning hardware, called twice, duplicates the expression in the output rather than
  sharing a wire. Synthesis performs CSE regardless. This is documented, not discovered.
- Deep expression trees emit as deeply parenthesised single-line expressions. The emitter may
  line-break for readability; it may not introduce signals.

**Named exceptions**, where Verilog forces a net to exist:

- Submodule output ports.
- Memory read ports.

In both cases the name derives deterministically from instance and port, so it is stable and
locally scoped — categorically unlike a global counter suffix.

### Module naming (R16)

- Base name is the Scala class name.
- Instance name is the `val` name (R1).
- **An array of instances** — `val slot = instances(n)(i => new Child(i))` — names them `slot_0` …
  `slot_{n-1}`, so a design may instantiate a count that is a *value* rather than a number of
  `val`s. The name still derives from the binding `val`; the index is the same numeric segment
  R7.1/R14.1 append when flattening a `Vec`, so an instance array and a port `Vec` read alike.
  This is not the counter suffix rejected below: the index is local to one declaration, and adding
  an instance array in one module renumbers nothing in another.
- **Parameterised modules receive a parameter-derived suffix:** `Fifo_d16_w8`. This is the only
  scheme that is both local and legible. A counter suffix (`Fifo_1`, `Fifo_2`) violates R1's
  locality clause — adding an instance in one file renumbers modules in another. A parameter hash
  is local but unreadable in a waveform viewer.
- Instances with identical parameters deduplicate to a single emitted module.
- An explicit `moduleName` override is available where the derived name is unwieldy. Overrides are
  collision-checked like any other name.
- **A group prefix** — `modulePrefix("m68k")` — precedes the derived name: `m68k_Fifo_d16_w8`. See
  R16.2.

**Encoding rules** (part of the stable-output contract):

| Type | Encoding | Example |
|---|---|---|
| `Int` | decimal, negatives prefixed `n` | `w8`, `off_n4` |
| `Boolean` | parameter name when true, omitted when false | `Fifo_registered` |
| Enum / sealed trait | case name | `mode_Burst` |
| Width-carrying hardware type | the width | `w8` |

### Name-contributing parameters (R16.1)

`Int`, `Boolean`, and enum parameters contribute to the derived name by default. All other types
are **excluded from the name but compared for module identity**.

Two instances differing only in a non-contributing parameter is an elaboration error, resolved by
marking the parameter as name-contributing or supplying `moduleName`.

*Rationale:* "every parameter contributes" is unimplementable — a `Bundle`, a config case class, or
a function parameter has no sensible string encoding, and the fallback is a hash, which is the
option already rejected. Marked-only sidesteps encoding entirely for the hard cases, needing only
equality. The failure mode is also better: loud, at elaboration, with a one-annotation fix, rather
than quiet production of unreadable names.

### Group prefix (R16.2)

```scala
abstract class M68kModule(args: ModuleArg*) extends Module(modulePrefix("m68k") +: args*)

class Fifo(depth: Int) extends M68kModule(param("d", depth))    // m68k_Fifo_d16
```

A Scala class is qualified by its package and an emitted module is not: Verilog has one flat
namespace, and R16 derives a name from the class's *simple* name. So `cpu.Fifo` and `soc.Fifo` ask
for one name. A prefix carries the qualification across the boundary — it says which subsystem a
module in a waveform belongs to, and it lets two subsystems each hold a `Fifo`.

- Declared as a base-constructor argument, so a group states it once on a base class and every
  member inherits it. A module says what it **is**; the base class says where it **lives**.
- At most one per module. A second is an elaboration error naming both.
- An explicit `moduleName` is a whole name and takes no prefix, so exactly one thing decides a name.
- A prefix is part of a Verilog identifier: letters, digits and underscore, not leading with a digit.
- Blackboxes are unaffected — R18 has a blackbox state its own name, which is the name the external
  module already carries, and renaming it would break the instantiation it exists to make.
- Identity (R16.1) is untouched. Two groups derive different names and so never reach the identity
  comparison; inside one group the comparison is what it was.

*Rationale for chosen rather than derived from the package:* derivation couples emitted names to the
source tree. The last segment is not unique across projects — two of them may hold a `periph` — and
the full package publishes the layout into the netlist, renaming a Verilog module every time a class
moves and churning every downstream baseline for no design change. Granularity is a property of the
design, not of the directory: a project may want one prefix or one per subsystem, and only a
declaration can say which.

---

## 2. Elaboration

### Typed elaboration context (R3)

No ambient global state. Hardware construction requires a `using` context; constructing hardware
outside a module is a **compile** error, and any function that builds hardware declares that fact
in its signature.

```scala
def counter(w: Int)(using Module): UInt =
  val cnt = Reg(UInt(w), init = 0)
  cnt := cnt + 1
  cnt
```

The context remains a mutable builder internally — it accumulates statements during elaboration —
but it is a *local* object threaded explicitly, not a thread-local. Two generators elaborate
independently; a generator can be unit-tested by handing it a fresh context.

Scala 3 context functions make the call site identical in length to the ambient-state version, so
the guarantee is free at the point of use.

**Condition scope** (`when` nesting) is internal builder state, not a separate capability. Nobody
writes helper functions that require being inside a `when`, so a third context parameter would be
noise without payoff.

---

## 3. Clocking and reset

### Clock domains (R4)

Every module has a default clock domain, inherited from its parent. `withClock(slowClk) { ... }`
scopes a different domain locally. **Every signal records the domain it was created in** — this is
what makes R5 possible without an external tool.

Clock domain is part of the module context rather than a separate capability. This is the
pragmatic choice; the alternative (a `ClockDomain` capability, making combinational-only functions
provable from their signature) was considered and rejected as too much ceremony for the payoff.

### A declaration may name its domain (R4.1)

`Reg`, `Wire` and `Fsm` take an optional `domain`:

```scala
given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)  // what a bare Reg gets

val count       = Reg(UInt(10), init = 0, domain = asyncReset)
val write_phase = Reg(UInt(2),  init = 0, domain = syncReset)
val data_latch  = Reg(UInt(8))                                  // the module's own
```

**Optional, and an override.** Omitted, a declaration resolves its domain exactly as it did before:
a `withClock` scope if one is open, else the `using` default. Supplied, it wins over both.
Innermost wins — the declaration's own argument, then the enclosing region, then the module — which
is the precedence R13.2 promised when it said the explicit override remains available.

`withClock` is not the alternative for a *named* declaration and never was: its body is
`ClockDomain ?=> Unit`, so a `val` declared inside cannot be named outside it, and R1 takes a
register's name from the `val` it is bound to. It remains the region default, and the only way to
name a domain for `inst`, memory ports and the CDC helpers.

*Not mandatory,* for R13's own reason: reset style is a property of a clock region, and 200
registers each declaring it is noise and a bug surface. *Not mandatory only where a module has two
domains* either — that would make a statement's legality depend on another statement's presence
(R6.7's objection), and adding a second domain to a module would break every `Reg` already in it,
which is the opposite of R13's one-line change.

*Not for `inst`.* A child's domain is stated by connecting its clock and reset (R13.1, R13.6),
which is the more explicit of the two statements; a second, parallel way to say it would leave two
spellings to keep in agreement.

### A clock region must govern a declaration (R4.2)

A domain is a property of *declared hardware*. `when`, `switch` and `:=` take no `ClockDomain`:
under R12 a register has one driver block, and that block's sensitivity list comes from the domain
recorded where the register was declared, not from where the assignment happens to be written. So a
region wrapped round statements alone governs nothing, and is an elaboration error
(`DOMAIN_SCOPE_EMPTY`):

```scala
val count = Reg(UInt(20), init = hold, domain = resetDomain)

withClock(resetDomain) {          // error: names a clock no declaration takes
  when(tick) { count := count - 1 }
}
```

The region is legal the moment something inside it *declares* — a `Reg`, `Wire` or `Fsm`, an
`inst`, a memory port, a CDC primitive — and a single one suffices however many statements
surround it. A declaration in a nested region counts for the outer one too: `withClock(a) {
withClock(b) { … } }` states `a` for `b`'s region.

*Rationale:* the failing form is not redundant, which is what makes it worth an error rather than a
lint. It reads as a second claim about a register that already named its domain, and a reader who
found the two disagreeing would have to guess which one reached the hardware. Nothing did: the
region was inert. Better to make the writable thing the true thing.

### A region is not overridden by a lexical `given` (R4.3)

Where a region is open it is consulted **before** the `using ClockDomain`, so a `given` written
inside a `withClock` block is not the innermost domain — it is the fallback the region has already
displaced. A declaration reached that way used to take the region's reset shape in silence. It is
now an elaboration error (`DOMAIN_SCOPE_SHADOWED`):

```scala
withClock(async) {
  given ClockDomain = sync        // error: `async` is open and wins
  val r = Reg(Bool(), init = False)
}
```

The check distinguishes a *third* domain from an enclosing one, because the two arrive identically.
A helper that declares hardware and closed over the module's given at its definition site carries
that given into every region it is called from, and R13.2 has always said the region wins — so a
`using ClockDomain` naming any domain an open scope also names is accepted unchanged. Only one no
open scope has heard of is refused, which is what a `given` written inside the block always is.

The escapes are the two spellings that mean it: `Reg(..., domain = d)` (R4.1) for one declaration,
or a nested `withClock(d) { … }` for a region. Outside a region the given-block form is untouched —
it remains the only way to name a domain for `inst` (R4.1), and `ConnectedClockSuite` holds it to
the same verdict as wiring the child's clock.

*Rationale:* R4.1 states the precedence as *declaration, then region, then module*. For one of the
three ways to write the region that was false, and silently: the mechanism has two halves —
`withClock` pushes builder state, a `given` supplies lexical evidence — and they are consulted in
that order rather than by nesting. This makes the spec's sentence true by refusing the case it
cannot honour, rather than by inventing a nesting depth the language does not expose.

### Reset is a clock domain property (R13)

A `ClockDomain` carries clock, reset signal, reset polarity, and reset kind
(synchronous / asynchronous / none). A register's reset behaviour comes from its domain, not from
its own declaration. `Reg(UInt(8), init = 0)` supplies only the *value*.

*Rationale:* reset style is a physical property of a clock region. Having 200 registers each
declare "async, active-low" is noise and a bug surface; switching a block from sync to async reset
should be a one-line change at the domain.

The sensitivity list follows mechanically from the domain:

```verilog
// async, active-low
always @(posedge clk or negedge rst_n)
  if (!rst_n) cnt <= 8'd0;
  else        cnt <= cnt + 1;

// sync, active-high
always @(posedge clk)
  if (rst)    cnt <= 8'd0;
  else        cnt <= cnt + 1;
```

A register with no `init` in a domain that has a reset is simply not reset. This is legitimate —
datapath registers usually should not be. *Not reset* reaches the sensitivity list as well as the
branch: the register keeps the clock edge and loses the reset edge with the value, because an
asynchronous edge with no branch to answer it would clock the input in on the reset rather than
hold, which is the opposite of what it is being asked for.

```verilog
// async, active-high domain — but this register declares no init
always @(posedge clk)          // not @(posedge clk or posedge rst)
  cnt <= cnt + 1;
```

### Clock and reset are explicit ports (R13.1)

No implicitly-added ports. R10 holds without exception: every port in the emitted Verilog is a
`val` the user wrote.

`Clock` and `Reset` are distinct types from `Bool`, so a clock cannot be used as data and a domain
can only be constructed from correctly-typed signals.

```scala
class Counter extends Module:
  val clk   = In(Clock())
  val rst_n = In(Reset(ActiveLow))
  val q     = Out(UInt(8))

  given ClockDomain = ClockDomain(clk, rst_n, Async)

  val cnt = Reg(UInt(8), init = 0)
  cnt := cnt + 1
  q := cnt
```

### Domain inheritance (R13.2)

A child module declares its own clock and reset ports (R13.1), but **instantiating it inside a
domain auto-connects them from that domain**. `withClock(slowClk) { val u = SlowUnit() }` wires
`u` to `slowClk`. Explicit override remains available.

This is a *connection* rule, not a naming one — R13.1 already secured the property that mattered
(no invented ports), and the auto-connection writes only the wiring the user would have written.

### Clocking traits (R13.3)

`SingleClock` and `DualClock` are library traits declaring the standard clock/reset ports and
supplying the domain `given`. Opt-in and readable, so R13.1's explicitness survives while the
per-module ceremony does not have to be retyped.

### `init` is the reset value, in every domain (R13.4)

`Reg(t, init = v)` loads `v` when the domain's reset is asserted, and nothing else. In a domain with
no reset (`ResetKind.None`) there is no reset to load it, so an `init` there is an error
(`INIT_WITHOUT_RESET`) whose fix names `powerUp` (R13.8) — the value a bitstream loads, which is
written on the declaration in any domain.

*Rationale:* R13 splits a register's reset in two, the shape belonging to the domain and the value
to the register. A domain with no reset has no shape, and the value needs somewhere to go — hardware
with no reset line is ordinary, a video shifter that must not come up white, a counter whose
power-up state is its only initialisation. This requirement first gave it the declaration: `init`
in such a domain was written as `reg [15:0] shifter = 16'd65535;`. That made one argument mean two
claims chosen by a property of a different object, and it failed exactly as R13.8 later said it
would: giving the domain a reset — the one-line change R13 promises — or moving the register into
a domain that has one withdrew its value at configuration and put it in the reset branch, with no
line of the register's source changed and no diagnostic anywhere. The value then arrives only when
the reset is first asserted, which for a reset derived from logic (R13.7) or raised only by a
button is a window in which the register runs from whatever the device loaded.

So each argument has one meaning. `powerUp` is kept wherever the register moves; `init` is kept
wherever there is a reset to load it and refused where there is not. A move between domains either
keeps what the source claims or does not elaborate.

A power-up value has two rules of its own, because a declaration is initialised with a *constant*
and is one net: a value that is not an elaboration-time constant (`POWER_UP_NOT_CONSTANT`), and an
aggregate one (`POWER_UP_AGGREGATE` — an aggregate is one net per leaf under R7.1/R14.1, and there
is no single declaration to initialise). R13.8 states them.

### A register may carry both, and they are two arguments (R13.8)

```scala
val delay = Reg(UInt(20), init = hold, powerUp = 0xFFFFF.U(20))
```

```verilog
  reg [19:0] delay = 20'd1048575;
  always @(posedge clk or negedge rstn) begin
    if (!rstn) begin
      delay <= hold;
```

R13.4 as first written left one thing undecided and named it: *a register that wants both wants
something this requirement does not decide.* This decides it. **`init` is the reset value and `powerUp` is the
declaration value**, and they are separate arguments because they are separate events — a bitstream
is loaded once at configuration and a reset happens whenever the machine is reset, and the values
need not agree.

- **`powerUp` is honoured in every domain**, reset or not, and it is the only way to reach the
  declaration: R13.4 gives `init` the reset branch alone.
- **It is held to the declaration's two rules**: a declaration is initialised with a *constant*
  (`POWER_UP_NOT_CONSTANT`), and an aggregate has no single declaration to carry a packed value
  (`POWER_UP_AGGREGATE`). A bare integer takes the register's width, as `init` does (R6.3).
- **The reset value is under no such rule** and never was — R13 writes it in a branch, which is
  ordinary logic, so `init` may be a signal. That asymmetry is not new; it is only visible now that
  the two can appear together.

*Rationale:* the shape is ordinary and had no spelling. A counter that holds a machine in reset for
100 ms must be non-zero the instant the device is configured, *and* must reload on every reset — and
if the two values differ, as they do when a simulation shortens the wait, there was nothing to
write. The alternative considered was to let `init` mean both, choosing by domain. That is what
R13.4 does for the no-reset case and it does not generalise: it would make one argument mean
"reset value" or "power-up value" depending on a property of a different object, and a design that
gained a reset would silently change which claim it was making. R13.4 did exactly that for the
no-reset case until the same argument was applied to it.

### A clock may be *derived*, once and explicitly (R13.9)

```scala
val coreClk = Wire(Clock())
coreClk := half.asClock
private val core = ClockDomain(coreClk, rst, ResetKind.Sync, ActiveHigh)
```

R13.1 makes `Clock` a distinct type so a clock cannot *accidentally* become data or data a clock,
and that stays. What it also did was leave a design that **derives** a clock with nowhere to go:
`Wire(Clock())` declared fine and nothing could be assigned to it, so a module could compute a
divided or gated clock and then find it unusable. That is R13.7's hole one type along, and this is
the same door.

- **There is no polarity to state**, which is the one way this differs from `.asReset`. A clock's
  active edge is the *domain's*, declared where the domain is; taking an edge here would be a
  second place to say it and two places to keep in agreement.
- **One bit**, for R13.7's reason: which bit of a vector is the clock is not something to guess,
  and a wider operand is a named error rather than a truncation.
- **The result needs a name before a domain can take it**, because a sensitivity list is written in
  terms of one — so it is assigned to a `Wire(Clock())` and the intermediate is the user's `val`
  (R2), exactly as R13.7 requires for a reset.

**What this does not decide.** Whether a domain built on a derived clock is a *new* domain for R5's
purposes is the user's statement, made where the domain is declared, exactly as it is for a clock
arriving on a port. The conversion makes the signal expressible and makes no claim about its
relationship to the clock it came from — a divided clock and its source are phase-related and a
gated one is the same clock, and neither fact is something a cast could know. Where it is known,
R5.6 is where it is said: `relatedClocks(clk, coreClk)`.

*Rationale:* found on a computer's top level, which builds its processor's 8 MHz clock out of the
system's 16 MHz and two enables:

```verilog
  reg core_clk = 1'b0;
  always @(posedge clk) begin
    if      (enPhi1) core_clk <= 1'b1;
    else if (enPhi2) core_clk <= 1'b0;
  end
```

That is not vendor IP or an oddity — it is how a machine whose peripherals run at the memory's rate
and whose processor runs at half of it is built, and there are two more of them waiting on a board
with four PLLs and one 50 MHz crystal.

### A reset is readable as data, once and explicitly (R13.5)

```scala
drive.resetLow  := !reset.asserted     // a child that spells it the other way round
buffer.resetHigh :=  reset.asserted
when(!reset.asserted & enable) { writeData := bus }   // holds while reset is asserted
```

`rst.asserted` is a `Bool` that is true while the reset is **active**, so an active-low reset comes
back already inverted. There is deliberately no way to ask for the raw *level*: a level means
nothing without the polarity beside it, and asking for one is exactly how a reset ends up driving a
child backwards.

R13.1 keeps `Reset` a distinct type so a reset cannot *accidentally* become data, and that stays.
But a hierarchy needs a reset to be data on purpose — a child declaring the opposite polarity, a
soft reset ORed in, a register that must *hold* through reset rather than be cleared by it, which
is what a shared `always` block gives every register in it that has no reset value. Refusing all of
that does not prevent a bug; it prevents a design, and R12's one-block-per-register model is what
makes the third case need spelling at all.

**And the rule is now enforced where it was not.** `Cat` never checked its parts, so `Cat(rst)` was
a way through — reachable by exactly the reader who has just had `!rst` refused. A clock or a reset
in a concatenation is an error, naming `.asserted` as the way out.

### An explicit clock connection is a domain statement (R13.6)

Connecting a child's clock port states which domain that child is in, and everything it drives
follows:

```scala
val u = inst(new SlowUnit())
u.clk   := slowClk        // this is the statement of u's domain,
u.rst_n := slowRst_n      // and `u.q` belongs to slowClk from here on
```

Reading `u.q` in the parent's own domain is then the R5 crossing it is, reported against the
connected clock. Where no explicit connection is made, R13.2's auto-connection is unchanged.

*Rationale:* a port connection is the more explicit of the two ways to say where a child sits, so
it cannot be the one that means less. R13.1's model is that every port is a `val` the user wrote and
nothing is implied; R13.2 adds auto-connection over that and promises the explicit override remains
available. That override arrived for the *wire* and not for the *fact* — the connection was
replaced and the parent-side nets `<instance>_<port>` kept the domain in scope at `inst(...)`.

**And those nets are the whole hierarchy check.** A port carries no domain (R13.1), so a child's own
view of its outputs is neutral by construction and the parent's view of them is the parent-side net.
That net's domain is the single place a cross-module crossing is decided; leaving it stale did not
weaken R5 across a boundary, it removed it.

The rule applies where the child declares **exactly one** input clock. With two there is no single
answer to which of them an output follows — the child knows and the parent cannot — so the
enclosing domain stands rather than half of it being guessed at. The reset follows the connected
reset where the child has exactly one, and decides nothing either way: a domain is its clock (R5.5).

### A reset may be *derived*, once and explicitly (R13.7)

```scala
val derived = Wire(Reset(ActiveHigh))
derived := (writing & (index === 9) & (data(7, 6) === 3) | hardReset.asserted).asReset(ActiveHigh)
private val derivedDomain = ClockDomain(clk, derived, ResetKind.Async, ActiveHigh)
```

```verilog
  always @(*) derived = (((writing & (index == 4'd9)) & (data[7:6] == 2'd3)) | hardReset);
  always @(posedge clk or posedge derived) begin
    if (derived) begin
      wr15 <= 8'b11111000;
```

The exact inverse of R13.5, and it exists for the case R13.5's own rationale
already names — *"a soft reset ORed in"*. That rule gets a reset **out** of
`Type.Reset` and into data; there was no way back, because R6.5 refuses
`Bool → Reset` and R21.4's conversion set stops at `.asUInt`/`.asSInt`/`.asBits`.
So a module could compute its own reset and then not use it: `Wire(Reset(…))`
declared fine and nothing could be assigned to it, and `ClockDomain`'s R13.1
type guard — which is right, and stays — turned that into a wall.

**The polarity is applied, not recorded.** The argument says what *asserted*
means for the result, so `ActiveLow` is built by inverting. That makes the round
trip exact — `x.asReset(p).asserted` is `x` for either polarity — and a
conversion that merely relabelled would let the two spellings disagree about
which level is active, which is the specific bug R13.5 refuses to permit.

**The result needs a name before a domain can take it**, because a sensitivity
list is written in terms of one. So it is assigned to a `Wire(Reset(…))` rather
than handed to `ClockDomain` directly, and the intermediate is the user's `val`
rather than one the library invented (R2).

**One bit.** A reset is a level; which bit of a vector is the reset is not
something to guess, and a wider operand is a named error rather than a
truncation.

*Rationale:* found porting a Zilog 8530, whose soft reset is a decode of a
register write — `assign reset = ((wreg_a | wreg_b) & (rindex == 9) &
(wdata[7:6] == 2'b11)) | reset_hw;` — used by nine `always` blocks as an
asynchronous reset. Writing it as the synchronous clear it resembles is *not*
equivalent there: the decode goes true partway through a CPU write and the
registers it clears reach `_irq`, so the two differ at a pin for a clock. A port
held bit-exact against that module has to be able to write what it writes.

*Considered and rejected:* a `ClockDomain` overload taking a `Bool` and a
polarity. It needs a name for the sensitivity list anyway, so it would have to
invent one (R2) or take the wire it is being handed — and the conversion is the
thing that is really missing, since `Wire(Reset(…))` was already declarable and
only its assignment was refused. Fixing the conversion fixes the general case
and leaves one way to say it.

---

## 4. Clock domain crossing

### CDC safety (R5)

Reading a signal from a foreign clock domain is an **elaboration error**. Crossings must go
through blessed primitives, which are the only constructs permitted to consume a foreign-domain
signal:

| Primitive | Use |
|---|---|
| `SyncChain` | single-bit levels |
| `AsyncFifo` | multi-bit data |
| `PulseSync` | single-cycle events |

Reset crossings count: an asynchronous reset released in the wrong domain is the same class of bug.

**A domain is its clock.** Two `ClockDomain` records naming the same clock signal are one domain
however they differ in reset signal, kind or polarity, and a read between them is not a crossing.
R13 splits a register's reset in two on purpose — the shape belongs to the domain and the value to
the register — so one clock legitimately carries more than one reset shape, and a peripheral
routinely does: an asynchronously reset status register beside a synchronously reset one beside a
pipeline stage nothing resets at all. Comparing whole records made those ordinary reads a crossing,
which is a metastability claim about two registers that share an edge and can never be one. A
*different* clock signal stays foreign even where the resets agree, which is the conservative half:
two nets carrying the same frequency are two nets — unless the module has said they are one source
(R5.6).

Reset-domain crossing is a different analysis and R5 does not claim it.

This is a genuine advantage over both SpinalHDL and Chisel, where CDC correctness is convention.
Because R4 records a domain on every signal, the check needs no external tool and no separate lint
pass.

### CDC report (R5.1)

The elaborator emits a report listing every crossing, its source and destination domains, and the
primitive handling it. Safe and unsafe crossings are listed separately so the unsafe list stays
short and reviewable.

### Unsafe escape hatch (R5.2)

A crossing may be explicitly marked to bypass the check. It requires a **mandatory** written
justification, is never silent, and every use appears in the R5.1 report.

```scala
val cfg = unsafeCrossDomain(cfgReg, "quasi-static, written only before enable")
```

An expression-level marker is preferred over a Scala annotation: it applies to a specific crossing
rather than a whole definition, it is visible at the line where the risk lives, and the
justification string flows into both the report and an `ASYNC_REG`-style comment in the output.

### `GrayCode` type (R5.3)

A distinct hardware type whose operations preserve the single-bit-change property (increment,
compare, convert to/from binary). `SyncChain` accepts multi-bit `GrayCode` where it rejects
multi-bit `Bits`.

*Motivation:* discovered by writing the async FIFO. Without it the primitive set is not
self-hosting — `AsyncFifo` itself must cross a multi-bit gray-coded pointer through a sync chain,
which R5 cannot distinguish from an unsafe crossing. The library must be able to implement its own
CDC primitives without resorting to R5.2.

### Dual-domain memory (R5.4)

A memory with ports in different clock domains is a blessed crossing. The elaborator checks that
the address pointers reaching it are themselves synchronised, so the blessing cannot be obtained by
accident.

### A domain is its clock in the type, not only in the check (R5.5)

`ClockDomain` carries two values that answer two questions, and they are separate:

```scala
final case class ClockRef(id: SignalId, name: String)                 // identity
final case class ResetSpec(id: Option[SignalId], name: Option[String],
                           kind: ResetKind, polarity: ResetPolarity)  // recipe
final case class ClockDomain(clock: ClockRef, reset: ResetSpec)
```

**Identity is the clock; the reset is a recipe carried along.** Every site asking *which domain is
this* compares `domain.clock`, or calls `isForeignTo` for the R5 relation with its neutrality.
Comparing whole domains remains legitimate — two records differing in reset are genuinely different
*recipes* — and is now visibly a different question rather than the same one spelled shorter.

*Rationale:* R5 ruled that a domain is its clock and fixed the one function that asks. Everywhere
else, case-class `==` on a flat record answered *were these two written the same way*, which agrees
with *are these one domain* exactly as long as a module holds one reset shape per clock — the
assumption R13 exists to make false. Three defects followed, all silent: a `WriteFirst` memory read
emitted a `ReadFirst` one because its bypass writes were selected by whole-record equality; a
memory's write-data CDC check was blessed away because two records on one clock counted as two
domains; and one memory emitted two `always` blocks on one edge, which is not the single-block
template BRAM inference is written against.

A type that answers only one of two real questions cheaply is what produced them. The fix makes
both askable and neither the default — deliberately not an `equals` override, which would have
fixed the identity sites by silently breaking the grouping ones.

### Related clocks (R5.6)

```scala
val clk      = In(Clock())     // 108 MHz, the memory
val mac_clk  = In(Clock())     //  27 MHz, the machine
val core_clk = In(Clock())     //  13.5 MHz, its processor
relatedClocks(clk, mac_clk, core_clk)   // three taps of one PLL
```

A module may state that two or more of its clocks share a source. For R5 the family is one clock:
a read between two of its members is not a crossing, needs no primitive and no marker, and the R5.1
report lists the family under **Related clocks**, so an empty Unsafe list can be read beside the
reason it is empty.

**What is stated, and what is not.** Only that the clocks come from one source — a PLL's taps, a
divided clock and what it was divided from — so that every edge of one is timed against the other
and a value either side presents is settled before the other side samples it. No ratio and no
phase: the check uses neither, the number lives with the PLL that makes it, and a copy in the RTL
would be a second place for it to be wrong. Whether the slow side sees every value the fast side
presents is a protocol fact, the same as with an enable, and stays the design's.

**It is a claim about the environment, and the environment is checked.** The statement belongs in
the module that has the clocks: its own ports and wires, never a child's input, which is a clock
the module hands *down*. A family naming two or more of a module's *input* clocks is a contract
about what the module is handed, and its parent is held to it when the parent freezes: the pins of
such a family must be connected from clocks the parent has itself declared one source, or from one
clock, else `RELATED_CLOCKS_REQUIRED`. The claim goes unchecked in exactly one place — the top,
whose parent is the board.

**Two statements that name a common clock are one family.** A `Wire(Clock())` made with R13.9's
cast joins one the same way a port does; the cast itself still claims nothing.

*Rationale:* found on the same machine as R13.9, one clock later. Its memory ran at four times
the machine and its processor at half of it, all three off one PLL, and every one of the
twenty-six wires between them carried `unsafeCrossDomain` with one of two sentences attached. The
hatch is for the exception and here it was the rule; and at the one place the machine's memory
cycle — an R31 interface — met its port, `<>` could not be used, because each leaf had to carry its
own justification. The sentences stated one fact, and the checker was the only party that could
not hear it.


### An input declared asynchronous (R5.7)

```scala
val rxd   = In(Bool(), Asynchronous)   // a serial line, driven by the other end's crystal
val level = SyncChain(rxd)             // the two flops the rule now insists on
```

A port carries no clock, and that neutrality is what makes a module's boundary free: the same
module is instantiated under whatever clock its parent has, and a read of an input is a read of
whatever the parent connected. One kind of input is not like that — the pin nothing in the design
clocks. A serial receive line, a button, a card-detect contact: its edges fall where the other end
puts them, and a register loaded straight from it can go metastable. Declaring the input
`Asynchronous` gives it a domain of its own, foreign to every clock, so reading it in clocked logic
is an R5 crossing and the synchroniser such a pin has always needed stops being a convention.

**`SyncChain` is its one reader.** `PulseSync` assumes a pulse one clock wide in a source domain and
`AsyncFifo` assumes a source clock to write on; a pin has neither, so both are refused naming this
rule rather than blessing a crossing on an assumption that cannot hold. R5.2's hatch still admits
one with its written justification, which is where a level the driver holds for a thousand clocks
goes. Two flops written out by hand are what the rule refuses: they are the right hardware and the
checker cannot tell them from an ordinary pipeline, which is the reason the primitive exists.

**It declares what the module may assume, not what arrives.** The marker is a statement by the
module about its own boundary — it assumes nothing about this pin's timing — so nothing propagates
across an instantiation. A parent may drive such an input from a register in its own domain, and
the child synchronises it anyway: two flops, and a claim about its source that it no longer needs
to make. A parent may equally pass its own asynchronous pin down unchanged, which is how a pin
reaches the module that synchronises it, and neither side declares anything on the way. A module
with no clock may read it, because nothing in it samples.

**The report shows it; the hardware does not change.** R5.1 lists it beside the crossings between
two clocks, with the domain named for what it is:

```
Safe
- rxd: asynchronous -> clk via SyncChain(2)
```

The marker is elaboration-only. The port's direction, width, name and position are what they were,
and a design carrying it emits byte-for-byte the Verilog it emitted without it — the two flops in
that report come from `SyncChain`, which is hardware the design asked for. The rule is opt-in for
the same reason: a pin that says nothing about itself is read as before, which is where every
project stands until it marks its pins.

*Rationale:* found writing a UART receiver, on the first pin in either dependent project that no
clock in the design drives. The check could not see it. Every port carried the neutral domain,
neutrality is compatible with every clock by construction, and so the one crossing in the design
that is certain to exist was the one crossing the checker had nothing to say about: two flops in
front of the receive line elaborated without comment, and the report that lists a design's
crossings listed none. A rule that is enforced everywhere except at the boundary where the outside
world arrives is enforced in the easy half.
---

## 5. Types

### Signal types (R6)

`Bool`, `UInt`, `SInt`, `Bits`, plus `Bundle` (named fields) and `Vec` (indexed).

**Widths are values, not types** — checked during elaboration. Errors name both signals, both
widths, and the source line where each was declared.

Type-level widths (`UInt[8]`, with `compiletime.ops.int` arithmetic) were considered and deferred.
They are the more interesting research direction and give better teaching feedback, but they
complicate every generic generator and degrade error messages when inference fails. Nothing here
forecloses adding such a layer above this one later.

Two rules attach, both serving R2 by keeping emitted Verilog literal:

- **No implicit width extension.** `a + b` with mismatched widths is an error, not a silent
  zero-extend. Resizing is explicit. This is the single largest source of "why is my Verilog wrong"
  bugs in HDLs that auto-extend.
- **No implicit signedness conversion.** `UInt` and `SInt` do not mix. `Bits` has no arithmetic at
  all. Conversions are named methods.

### Literal width from context (R6.1)

Integer literals take the width of the expression they appear in, so `wbin + 1` needs no
annotation. Explicit width remains available (`1.U(8)`). A literal too large for its context
is an elaboration error naming the required and available widths.

**Guard:** a literal *nested* in an expression never takes its width from the assignment target. In
`x := a + 1` the literal takes `a`'s width, not `x`'s — so widening happens where the user wrote
`resize`, never silently through a literal. R6's no-implicit-extension rule is thereby intact. A
literal that is the whole right-hand side has no expression to widen and is R6.3's.

### Bit-select requires a named signal (R6.2)

Slicing or indexing an anonymous expression is an elaboration error naming the operand and showing
the `val` to introduce.

```scala
val carry = (a + b)(8)        // error
val sum   = a + b             // fix
val carry = sum(8)
```

Truncation at an assignment target is unaffected, since Verilog truncates natively:
`x := (a + b).resize(8)` needs no intermediate.

**One selection, one spelling.** A bit-selection reaching the emitter has one Verilog form no
matter which surface built it — a written `sig(hi, lo)`, a written `sig(i)`, or the MSB pick inside
R21.4's sign extension. A one-bit window is therefore a bit-select, `opcode[10]`, never the
degenerate part-select `opcode[10:10]`; the two are the same 1-bit unsigned value in Verilog-2001,
so the choice is R12's and R12 picks the form a reader of the source would have written. The same
unification is what composes a selection of a slice into one absolute selection, Verilog having no
part-select of a part-select.

*Rationale:* the alternatives are mask/shift emission (output stops resembling the source) or
auto-naming the intermediate (inventing a signal, forbidden by R2). Writing the async FIFO produced
**zero** expression bit-selects, because binding intermediates to `val`s is what one naturally
writes — strong evidence that rejection costs almost nothing.

### A literal that is the whole right-hand side takes the target's width (R6.3)

`x := 2.U` and `Reg(UInt(32), init = 0.U)` need no annotation: the literal is the entire value
being assigned, so the target's width is the only width it could have. The same applies to a
child's input port, which is where a parent assigns it (R23). The value is range-checked against
that width like any other literal.

This is a **leaf rule**, not a downward propagation. It applies to a literal and nothing else, so
`x := Mux(s, 1.U, 2.U)` and `x := Cat(a, 1.U)` remain errors and every nested literal still takes
its width from its sibling. R6.1's guard is thereby narrowed to the case it was written for — a
literal inside an expression, where inferring from the target would silently widen the operand next
to it — and left at full strength there.

A literal still never adopts a signedness (R21.7). `.U` and `.S` fix it at the literal, so a target
whose kind disagrees offers no width at all and `x := 2.S` on a `UInt` stays the error it was.

*Rationale:* the guard's hazard requires a second operand to widen. At the top of a right-hand side
there is none, and the range check already covers the only way a whole-RHS literal can be wrong.
The same distinction is why R6.2's narrowing `resize` needs no bit-addressable operand when it
forms the entire right-hand side.

### A bare `Int` takes its width and its kind from context (R6.4)

A constant may be written as a plain Scala `Int`. It arrives with neither width nor kind and takes
both from the position it appears in, at the same moment and from the same place:

```scala
writeAddrValue := 2
funct3 === 5
is(2)
Reg(UInt(8), init = 0)
```

`.U` and `.S` remain, and fix the kind wherever the context should not decide it. A property the
literal already carries is never overridden, so `s + 1.U` on an `SInt` is still R6's error, named
at the operator where both sides can be shown.

The kinds a literal can take are `UInt`, `SInt`, `Bits`, and `Bool` — the bit-typed four.
`GrayCode` is excluded by R5.3, aggregates by R7.2, and clocks and resets by R13.1.

This is the only spelling that reaches a `Bits` or `Bool` context, neither of which has a literal
suffix: `mask === 5.U` is a kind mismatch and there is no `.Bits` to write instead, so before R6.4
comparing either against a constant had no working form at all. (`Bool` since also has the named
constants of R6.6, which are the same literal under a name; `Bits` has this spelling and no other.)

*Rationale:* inferring a kind is safe for the same reason inferring a width is — a constant's value
means the same thing in every kind it can land in. What must not be papered over is a value the
kind cannot represent, and that is unchanged: a negative against any unsigned kind is R21.7's
error however it was written. The check that keeps this honest at an assignment is R6.5; without
it, a bare `Int` would be a way to change kind for free.

### An assignment admits no implicit kind conversion (R6.5)

`x := e` requires `e`'s kind to match `x`'s, exactly as an operator requires its operands to match.
Until this rule, assignment compared widths only — `q := s` with `s: SInt(8)` and `q: UInt(8)` was
accepted silently while `q === s` had always been rejected, making `:=` the one place in the
language where a kind changed for free.

The kind check runs only once the widths agree, so one mistake yields one diagnostic and a width
error keeps priority.

**One exception:** `Bool` is interchangeable with a one-bit unsigned kind. A bit-select is typed
`Bool` (R6.2) while a one-bit port is naturally declared `UInt(1)`, so `q := a(0)` would otherwise
need a cast that protects nothing — the two emit identically. `SInt` is outside the exception at
every width including one, because a one-bit `SInt` holds 0 or -1: a different value, not a
different spelling of the same one.

*Known cost, accepted:* a generic helper that slices its argument fails depending on how it is
called (`hi(a + b)`), and the fix belongs at the call site rather than in the helper. R19's
related-locations format carries both locations in the message.

Enforced **structurally** by R17, where `Slice` and `Index` take a signal reference rather than an
expression — the rule cannot be violated by a backend or a future pass.

### `True` and `False` name the one-bit constants (R6.6)

A one-bit signal carries a condition, not a number, and the constants it takes are written as
conditions:

```scala
memWrite := True
retValid := False
val frame = Reg(Bool(), init = False)
```

`True` and `False` are **literals** of kind `Bool` and width 1, both fixed at the literal exactly as
`.U` and `.S` fix a kind. They are not a type, not an IR node, and not an emission rule: `x := True`
emits the `1'b1` that `x := 1` and `x := 1.U(1)` already emitted, and every rule that governs a
literal governs these — in an assignment, a register's `init`, a case label, a `Mux` arm, a `Cat`
part, or an instance port connection.

**The width is fixed, not inferred.** A `Bool` is one bit by definition, and an open width would be
taken from the target: `word := True` on a `UInt(8)` would emit `8'b1`, with R6.5's
`Bool`/one-bit-unsigned exception passing the kind and nothing left to catch it. Fixed at one bit,
that assignment is R6's width error — while `Reg(UInt(1), init = False)` still holds, since a single
bit is exactly what the exception was written for.

The kind is `Bool`, so the pair reaches a one-bit unsigned target through that exception and reaches
nothing else. `word === True` on a `UInt(8)` is the kind mismatch `word === someBool` has always
been, and the fix is the one that was always right: declare the signal `Bool()`.

Capitalised because `true` and `false` are Scala keywords, and only because of that.

*Rationale:* nothing here is newly writable — R6.4 already lets `flag := 1` take both properties
from the target — and that is the point. A control signal's constant should say *asserted* or
*deasserted*, and `1` says neither: the reader of `memWrite := 1` must know the signal's width
before knowing that a condition was meant. This is R24's shape, a surface that names what the
language could already express, with byte-identical output as the check that it stayed one.

*Considered and rejected:* a `given Conversion[Boolean, Expr]`, admitting the lowercase
`flag := true`. It would also admit every *accidental* Scala-level boolean, and those are worth
more as compile errors: `when(a == b)` on two signals does not compare hardware — `==` is Scala's
reference equality, not `===` — and today it does not compile, where under the conversion it would
silently become the constant `1'b0`. A `.B` suffix on `Boolean` was rejected for a smaller reason:
the suffix family exists to fix a kind a literal would otherwise take from context, and a named
constant has no such need.

### A part-select is an assignment target, on a register (R6.7)

`sig(hi, lo)` names a contiguous window of a bit-vector, and the window is writable:

```scala
val counter = Reg(UInt(16), init = 0x5550)
counter      := latch                      // the whole register
counter(7, 0) := dataIn                    // and the low half, refining it
```

```verilog
  always @(posedge clk) begin
    counter <= latch;
    if (write) begin
      counter[7:0] <= dataIn;
    end
  end
```

The window is constant, the operand must be bit-addressable (R6.2), and the result is unsigned of
the window's width — the same three properties `sig(hi, lo)` has as a *read*, because it is the
same handle. A one-bit window emits `x[7]` rather than the degenerate `x[7:7]`, exactly as R6.2
already decided for a read, so one bit has one spelling whichever surface named it.

**Registers only.** On a register, the bits a write does not name hold their value — that is R11.1
read one bit at a time. On a wire they are a latch, which is the whole of what R11 exists to make
loud, and R11 could not see it: R11 asks whether a signal was *driven*, and a part-select does
drive it. So the rule is the kind of the signal rather than the shape of the block around it, and
the combinational case is `PARTIAL_WRITE_COMBINATIONAL` with `Cat(...)` named as the fix. This is
R14.2's shape and R14.2's reasoning: the write that only a register can take is decided at the
signal, once.

Two more refusals attach. A bound outside the signal is `PARTIAL_WRITE_OUT_OF_BOUNDS`. A window on
an element of a `Vec` is `PARTIAL_WRITE_VEC_ELEMENT`, because Verilog-2001 has no bit- or
part-select of a memory element — a bundle *field* is unaffected, since R7.1 flattens it to a plain
name.

**Selecting inside a window stays R6.2's error.** `sig(15, 8)(3, 0)` was an anonymous selection
before this rule and remains one. The emitter can already fold a slice of a slice into one absolute
selection, so admitting it would be writable; it is not admitted because the surface did not have
it and R6.2's advice — bind a `val` and select once — is the clearer source either way.

*Rationale:* the asymmetry was the bug. `sig(i)` returned a handle and could be written; `sig(hi,
lo)` returned a bare `Expr` and could not, though Verilog spells both as one part-select and a
design that needs one usually needs the other. What that cost was not expressiveness — a whole-word
`Cat` of a `Mux` says the same thing — but *correspondence*: R12's promise is that the emitted
`always` block reads like the source, and a port held line-for-line against the Verilog it replaces
has to be able to write the two statements the original wrote. Found porting a 6522, whose Timer 1
reloads a 16-bit counter from a pair of latches and then overwrites the low half from the bus when
a write lands on the same enable; expressed without this it needs a named intermediate holding the
value the first statement would have produced, and that intermediate is a place for a
transcription error to live.

*Considered and rejected:* admitting a partial write on a wire when the driver block already writes
the whole signal unconditionally before it — the R11 default-before-conditional idiom, one level
down. It is sound and it is more permissive, and it was rejected because it makes the legality of a
statement depend on a *different* statement's presence and position, which is the kind of rule a
reader has to simulate rather than read. Registers only is one sentence, and `Cat` is what a
combinational word wants to be written as anyway.

### A runtime selection on a packed bit-vector (R34)

A named bit-vector may be selected by a **runtime** index or offset, not only by a constant:

```scala
val word = In(UInt(32))
val idx  = In(UInt(5))
val bit  = word(idx)          // one bit, the bit idx names — word[idx]
val lane = word(idx, 8)       // eight bits from idx — word[idx +: 8]
```

```verilog
  assign bit  = word[idx];
  assign lane = word[idx +: 8];
```

The width of a window is a compile-time constant; only the offset is runtime. `word(idx)` is the
one-bit case, spelled the same way a constant bit-select is (R6.2), and `word(idx, 8)` its window;
both emit Verilog-2001's own indexed select, `word[i]` and `word[i +: w]`, so the emitted text reads
back as written (R12).

**A restart at the low bit is not a window.** The offset names the window's low bit, so `word(idx, 8)`
is `word[idx +: 8]`, not `word[idx -: 8]`. A downward select would make the offset's meaning depend
on the width, which is exactly the reading a reader has to simulate rather than read; the surface has
no spelling for it.

**The operand must be a bit-vector.** A `Vec` element has no part-select in Verilog-2001 (R14.1), so
the runtime window is a scalar `UInt`/`SInt`/`Bits`/`GrayCode` operation; a runtime element select on
a `Vec` stays R14.2's separate form.

**One selection, one spelling** (R6.2). A runtime bit-select is a bit-select: it emits `word[i]`, never
a generated mux or a shift-and-mask, for the same reason R6.2 gives — the emitted text is what a
reader of the source wrote. The result of a bit-select is `Bool`; the result of a window is unsigned
of the window's width, exactly as `sig(hi, lo)`'s is.

**A runtime window is an assignment target, on a register.** `word(idx, 8) := d` drives part of the
signal, so the rest must mean *hold*, which only a register means — the same rule and the same reason
R6.7 gives a constant window. On a wire it is `PARTIAL_WRITE_COMBINATIONAL`; a width that does not fit
the base is `PARTIAL_WRITE_OUT_OF_BOUNDS`; a window reached through a `Vec` element is
`PARTIAL_WRITE_VEC_ELEMENT`. A *single* runtime bit on a target is R14.2's dynamic write, not this
rule: a one-bit write to a bit-vector is the whole of that bit, so it is legal like any bit-select.

**A window is the last step of a path** (R6.7). Verilog has no selection inside a part-select, so
`word(idx, 8)(3, 0)` is R6.2's anonymous-selection error, not a composition.

**A plain Verilog-2001 indexed part-select** is the lowering, not a mux tree or a shift-and-mask:
`word(i)` already used it, so extending it to `word(i, w)` keeps one word selecting one way whatever
the width. Its one cost is recorded rather than hidden: `+:` is a spelling the library had not emitted
before, and the write form of a variable part-select has weaker tool support than the read form — which
is why the write side is held to the register-only rule above.

### Complete expression surface (R21)

R6 says which types exist and forbids implicit extension and signedness conversion. R21 says what
can be *written* with them: the operator set, and for every operator an unambiguous operand rule,
result type, result width, and emission. Discovered by writing a PicoRV32 datapath against R6 —
Appendix D records what the exercise found.

Two closure properties hold across the whole surface:

- **Operand-kind completeness.** Every operator accepts a named signal, an anonymous expression, or
  a literal on either side, in every combination. An operator that exists for `a + b` but not for
  `(a - b) & mask` is not a surface, it is an accident of which overloads someone happened to write.
- **Type-directed, never host-directed.** Signedness comes from the operands' ScalaHDL types. Never
  from the sign of a Scala `Int`, and never from Verilog's context-determined sign propagation
  (R21.6). No expression's meaning depends on the host language or on the backend's sizing rules.

| Operator | Operand types | Operand rule | Result |
|---|---|---|---|
| `+` `-` | `UInt`, `SInt` | same kind, same width | operand kind, width `w` |
| `*` | `UInt`, `SInt` | same kind, **widths may differ** | operand kind, width `w1 + w2` (R21.3) |
| `&` `\|` `^` | `UInt`, `SInt`, `Bits`, `Bool` | same kind, same width | operand kind, width `w` |
| `~` | `UInt`, `SInt`, `Bits`, `Bool` | — | operand type |
| `!` | `Bool` | — | `Bool` |
| `<<` `>>` | `UInt`, `SInt`, `Bits` | amount unsigned, any width | left operand's type and width (R21.1) |
| `>>>` | `SInt` | amount unsigned, any width | `SInt`, left operand's width (R21.1) |
| `===` `=/=` | any bit-typed | same type, same width | `Bool` |
| `<` `<=` `>` `>=` | `UInt`, `SInt` | same kind, same width | `Bool` (R21.2) |
| `orR` `andR` `xorR` | `UInt`, `SInt`, `Bits`, `Bool` | — | `Bool` |
| `Cat(…)` `Fill(n, x)` | any bit-typed | every part width-known | `UInt`, summed width (R21.5) |
| `.resize(w)` | `UInt`, `SInt`, `Bits` | — | operand kind, width `w` (R21.4) |
| `.asUInt` `.asSInt` `.asBits` | any bit-typed | — | requested kind, width unchanged (R21.4) |
| `Mux(sel, a, b)` | `sel: Bool`; arms any bit-typed | arms same type, same width | the arms' type |

Type admission is exact and follows R6's reasoning rather than restating it:

- **`Bits` has no arithmetic but does have bitwise operations.** `&`, `|`, `^`, `~`, shifts,
  equality, reductions, `Cat`, and `resize` are all legal on `Bits`; `+`, `-`, `*`, and ordering
  comparisons are not. R6 forbids arithmetic on a raw bit vector because arithmetic needs a
  signedness interpretation the type deliberately withholds — masking bits needs no such
  interpretation. Ordering is arithmetic in this sense and is therefore excluded: a `<` on `Bits`
  could only mean an *accidentally* unsigned comparison, which is exactly what R21 forbids.
- **`GrayCode` admits only its preserving operations** (R5.3), which this surface does not extend.
- **`Clock` and `Reset` admit none of it** (R13.1). `Bundle` and `Vec` are not a fourth exclusion:
  an aggregate reads as its packed `Bits` (R7.2), so it is admitted exactly where `Bits` is —
  concatenation, replication, bitwise, equality, reductions, `Mux` arms — and refused exactly where
  `Bits` is, since arithmetic and ordering need a signedness interpretation no aggregate carries.

Every operator builds an anonymous expression tree and declares nothing, preserving R2: the emitted
wire count still equals the named-`val` count. Two members of the surface emit a bit-selection and
therefore inherit R6.2's rule that the selected operand must be **bit-addressable** — a named
signal, a bundle field or vec element of one, or a slice of either — namely narrowing `resize` and
sign-extending `resize` (R21.4). Everything else composes over arbitrary anonymous trees.

*Rationale for adding this as one requirement rather than growing operators ad hoc:* the operators
are not independent. Shift result width, multiply result width, and `resize` are a single system —
the answer to "how wide is `a * b`" determines whether `resize` is needed to store it, and the
answer to "what does `>>` mean on `SInt`" determines whether the cast surface must offer a signed
reinterpretation. Specifying them together is what makes the emission invariant in R21.6 provable.

### Shifts are explicit about arithmetic versus logical (R21.1)

`>>` is a **logical** shift on every admitted type: zero fill, always. `>>>` is an **arithmetic**
shift, legal on `SInt` only; `>>>` applied to `UInt` or `Bits` is an elaboration error naming
`.asSInt` as the fix. `<<` fills with zeros and needs no variant.

The result takes the **left operand's type and width**. Bits shifted past the width are discarded,
matching Verilog's native truncation into a fixed-width target and R6's treatment of `+`, whose
carry bit is likewise dropped.

The shift amount is a separate operand and is **exempt from width agreement** — it is unsigned and
of any width. Together with the full product (R21.3) it is one of exactly two exemptions from R6's
rule, and it is exempt for a different reason: the amount is not a value in the result's number
system at all. An `SInt` shift amount is an error: a negative shift has no meaning worth guessing.

```scala
u >> n     // UInt(32) >> UInt(5)  → UInt(32), zero fill
s >>> n    // SInt(32) >>> UInt(5) → SInt(32), sign fill
s >> n     // legal: shifts the two's-complement pattern with zero fill
u >>> n    // error: arithmetic shift on UInt; write `u.asSInt >>> n`
```

*Rationale:* the alternative — one `>>` whose meaning follows the operand's type — reads more
compactly but makes the single highest-consequence bit of a datapath (SRL versus SRA) invisible at
the call site and dependent on a declaration elsewhere. RISC-V spends two distinct instructions on
that distinction; the surface should too.

### Ordering comparisons are signed by type, never by accident (R21.2)

`<`, `<=`, `>`, `>=` yield `Bool`. Operands must agree in kind and width, exactly as `+` does. Two
`UInt` operands compare unsigned; two `SInt` operands compare signed. There is no third case:
`Bits` and `Bool` are not ordered (R21), and mixing `UInt` with `SInt` is R6's existing error.

Signedness reaches the backend explicitly (R21.6), never through a Verilog `signed` declaration.
The pair `SLT`/`SLTU` — the same bits, two answers — is the reason this rule is stated separately
rather than folded into R21's table: a comparison that silently changed its answer when a signal's
declaration changed would be the single most expensive defect this specification could permit.

### Multiplication yields the full product (R21.3)

`a * b` has width `w1 + w2`. The product is **lossless**: no bit of either operand is discarded and
no result bit is silently dropped.

This is the one arithmetic operator whose operands may differ in width without an explicit
`resize`, and the exemption follows from losslessness — R6 forbids implicit extension because it
hides a *decision* about which bits survive, and a full product decides nothing.

```scala
val prod = a * b            // UInt(32) * UInt(32) → UInt(64)
mulh := prod(63, 32)        // MULH: the high half, by R6.2 bit-select on a val
mul  := prod.resize(32)     // MUL: the low half, explicitly narrowed
```

Storing a full product in an operand-width signal is therefore an explicit `resize`, and the error
when it is omitted names both widths (R6). `MULHSU`'s mixed signedness is not an exception to R6:
it is written by widening both operands to a common signed width first, which makes the sign
treatment of each operand visible in the source.

*Rationale:* the alternative — `max(w1, w2)`, consistent with `+` and `-` — makes the common `MUL`
case terser and the `MULH` family unwritable without manually pre-widening both operands, which is
the same ceremony moved somewhere less visible and applied where it is easier to get wrong.

### One `resize`, and casts that preserve representation (R21.4)

Two distinct operations, deliberately spelled differently because they differ in what they change:

- **`.resize(w)` changes width and preserves kind.** Widening a `UInt` or `Bits` fills with zeros;
  widening an `SInt` replicates its sign bit; narrowing keeps the low `w` bits. The extension policy
  is read off the operand's type, so no call site chooses it and no call site can choose wrong.
- **`.asUInt` / `.asSInt` / `.asBits` change kind and preserve width and bit pattern.** They are
  reinterpretations, never conversions: no bit moves, no bit is added, and `-1.S(8).asUInt` is
  `255`. They are how a value crosses R6's signedness barrier, and the only how.

Composing the two covers every conversion the surface needs, in an order the reader can see:
`b.asSInt.resize(32)` sign-extends, `b.resize(32).asSInt` zero-extends then reinterprets, and the
two are different values whenever `b`'s top bit is set — which is precisely why they must be
written differently.

Narrowing and sign-extending both emit a bit-selection, so both require a **bit-addressable**
operand under R6.2: a named signal, a bundle field or vec element of one, or a slice of either.
Anything else — the result of an operator, a `Mux`, a `Cat` — is bound to a `val` first, with the
same diagnostic and the same fix R6.2 already provides. One exception, already promised by R6.2:
a narrowing `resize` that forms the entire right-hand side of an assignment emits no selection at
all, because Verilog truncates natively at the target, so `x := (a + b).resize(8)` stays legal on
an anonymous operand.

*Rationale for one `resize` over `zext`/`sext`/`truncate`:* the extension policy is already
determined by the operand's type, so naming it again at the call site adds a way to disagree with
the type system without adding a way to say anything new. R19's fix text and R6.2's own example
already spell it `resize`.

### Concatenation and replication (R21.5)

`Cat(a, b, …)` yields a `UInt` of the summed width, first argument in the most significant bits —
the same order R7.2 packs bundles in, so a hand-written concatenation and a read of the bundle
itself never disagree. `Fill(n, x)` replicates `x` `n` times and yields a `UInt` of width `n * w`.

Both are one surface and admit one set of parts: `Cat` emits `{a, b}` and `Fill` emits `{n{x}}`, so
a type that is not a bit pattern is refused in either. A clock or a reset is the case that matters,
because `Cat(rst)` is the shape somebody writes exactly when the operators have refused; R13.5's
`.asserted` is the way through, and it says which level it means.

Every part must have a **known width**. Because a concatenation's width is the sum of its parts, a
part whose width was inferred would silently change the width of the whole; an unwidthed literal in
a `Cat` is therefore an elaboration error naming the part and the required explicit form (R21.7).

### Signedness is applied per operation, never inherited from a declaration (R21.6)

`SInt` is emitted as an ordinary unsized-signedness Verilog vector — no `signed` keyword on the
declaration. Every signed operation instead wraps its own operands: `($signed(a) < $signed(b))`,
`$signed(($signed(a) >>> n))`, and the signed forms of `*` and sign-extending `resize`. Unsigned
operations wrap nothing. `.asUInt`/`.asSInt` emit their operand unchanged, which is exactly correct
under this rule and is why reinterpretation costs nothing.

`>>>` wraps its **result** as well as its operand, and it is the only operation that does. Wrapping
operands is enough for an operation whose bit pattern is the same read signed or unsigned — `+`,
`-`, `<<`, `>>`, and the bitwise operators are, `*` is because R21.3 widens both operands to
`w1 + w2` first, and a comparison's operands form their own signedness context. An arithmetic shift
is the exception: it is *defined* by the signedness of the value it shifts. A bare
`($signed(a) >>> n)` appearing in `... + b` with an unsigned `b` is therefore re-typed as unsigned
along with everything else in that expression (hazard 1 below), and `>>>` on an unsigned operand is
a logical shift — the operation silently becomes the one R21.1 exists to distinguish it from. The
operand of `$signed` is self-determined, so the outer wrapper is the boundary the enclosing context
cannot reach through. R12 prefers the plainest rendering that is correct, which is why no other
operation is wrapped.

This makes two Verilog hazards structurally unreachable rather than merely avoided:

1. **Sign contamination.** A Verilog expression mixing a signed operand with an unsigned one is
   evaluated *unsigned* in its entirety, silently, and that decision propagates down into the
   operands. Since no declaration is signed, no operator's meaning depends on a sibling operand's
   declaration. What remains reachable is contamination of an explicit `$signed(...)` by the
   expression it sits in, which is why `>>>` — the one operation whose result differs between the
   two readings — wraps its result as well.
2. **Context-determined sizing.** Verilog sizes an operand to its context, so the same subexpression
   can produce different values in different contexts. R6's width-agreement rule already forces an
   expression's context width to equal its stated result width everywhere — with one exception,
   `*`, whose result is wider than its operands. R21.3's emission closes it by widening both
   operands to `w1 + w2` explicitly before multiplying, so every operand is self-determined.

Together these give the invariant the emitter can be tested against: **every emitted expression's
value is independent of the context it appears in.**

*Rationale for not declaring `SInt` nets `signed`:* it would make correctness depend on a property
of a declaration far from the operator, reintroduce hazard 1 at every boundary with an unsigned
signal, and change the emission of designs already written. Applying signedness at the operator
keeps the R12 rule that emitted Verilog reads like the source that produced it.

### Literals participate without widening or truncating anything (R21.7)

R6.1 gives a literal its width from its sibling operand and stops at assignment boundaries. R21
adds operand positions that R6.1 did not have to consider, and each needs an answer:

| Position | Literal's width |
|---|---|
| `*`, ordering comparison, `===`/`=/=` | the sibling operand's width, as in `+` |
| shift amount | the literal's **own minimal width**, `max(1, bitLength)` |
| `Mux` arm | the other arm's width; two literal arms is an error |
| `Cat` / `Fill` part | none — an unwidthed literal here is an error (R21.5) |

The shift amount is the only position where a literal's width comes from its value rather than its
context, and it is safe there for the reason it is exempt from width agreement (R21.1): the amount
is not a value in the result's number system, and a wider encoding of the same amount cannot change
the result.

Two further rules make literal handling total rather than best-effort:

- **Width must be knowable through the whole surface.** A literal's sibling may be a slice, a cast,
  a `resize`, a shift, a `Cat`, or a product; `instr(31, 20) + 1.U` must resolve `1` to 12 bits.
  Any position where the elaborator cannot compute a sibling's width is a defect in the elaborator,
  not a legitimate "uninferrable literal" error.
- **A suffixed literal never adopts a sibling's signedness.** `.U` and `.S` fix it at the literal,
  and mixing remains R6's error. A *bare* `Int` has no signedness to keep and takes the context's
  kind under R6.4; what neither may do is represent a value the kind cannot hold, so a negative
  against any unsigned kind is an error however it was written. A signed literal's admissible range is the two's-complement range of
  its width, `-2^(w-1) … 2^(w-1) - 1`, inclusive at both ends and checked identically whether the
  width was inferred or written explicitly. A negative signed literal emits with the sign outside
  the sized constant — `-32'sd1`, never `32'sd-1`, which is not Verilog at all.

### Bundles (R7)

Named, ordered field groups. **Fields carry no direction.** Direction is applied to a bundle
*instance* at the port declaration site (`In(...)` / `Out(...)`) and applies uniformly to all
fields.

```scala
case class AxiLiteAddr(w: Int) extends Bundle:
  val addr = UInt(w)
  val prot = Bits(3)
```

*Consequence, accepted:* a bidirectional interface cannot be one bundle used from both ends, since
ready and valid travel opposite ways. Either define paired bundles or declare fields individually
per port. More boilerplate, but a bundle's direction becomes a single fact readable off the
declaration rather than something resolved per field. R31 recovers the ergonomics without giving
that up: an `Interface` writes each direction once, from the master's side, so it is still one fact
per leaf readable off the declaration, and a bundle stays what an interface's field carries.

**A bound bundle is the signal it is bound to, and a reference to it**, wherever either is
expected. So a whole bundle is read by naming it:

```scala
out := in                 // an assignment's source
u.req := request          // ...and a port connection
```

Both conversions exist because Scala will not chain one into the other, and every *read* of a whole
bundle wants the second. There is no accessor beside them: the bundle is the signal, and reading it
is R7.2's question, not this rule's.

### Flattening rule (R7.1)

A bundle field's Verilog name is the path from the named root, joined by `_`. `Vec` in the path
contributes a numeric index.

```
val req = AxiLiteAddr(32)   →   req_addr, req_prot
nested                      →   req_inner_addr
vec of bundles at a port    →   req_ch_0_addr
```

Deterministic, reversible, and collision-checked under R1.

### Packing follows SystemVerilog packed structs (R7.2)

The first-declared field occupies the **most significant** bits; the last-declared field the least.
A bundle is bit-equivalent to the concatenation of its fields in declaration order.

```
Bundle { a = Bits(4); b = Bits(4) }, a = 0xA, b = 0x5   →   8'hA5
```

Nested bundles and vectors pack recursively by the same rule. `Vec` packs descending — index *n-1*
in the high bits, matching a SystemVerilog packed array declared `[n-1:0]`.

*Rationale:* SystemVerilog packed-struct layout is already specified, so it is inherited rather
than invented. The packing becomes plain concatenation and slicing in the output (serving R2), and
anyone who knows SystemVerilog knows the layout without reading documentation.

**An aggregate reads as that word.** A bundle *is* the packed vector, so it is one wherever an
expression is expected, and the result is `Bits` of the aggregate's width — a bit pattern with no
signedness, which `.asUInt` asks for as it does everywhere else (R21.4).

```scala
q := Cat(pair, pair)        // {{p_a, p_b}, {p_a, p_b}}
q := Mux(sel, a, b)         // two payloads, one mux
q := (a === b)              // ...and compared as the words they are
q := pair.asBits            // the same word, said explicitly
```

Verilog-2001 has no name for the whole — R7.1 flattened it into one wire per leaf — so what the
output spells out is that flattening, not a conversion the caller performed. The rewrite happens
once, on the elaborated module, before anything else looks at it; a leftover aggregate reaching the
type-admission table is a miss in that rewrite and is refused there (R21) rather than emitted as a
root name no module declares.

**Writing one is a different operation.** A whole-aggregate assignment and a whole-port connection
stay structural: one statement over a preserved aggregate (R17), paired leaf by leaf, with R14
checking that the two shapes agree. Bits reach an aggregate through `unpackBits` and nowhere else.

The asymmetry is deliberate, and it is the one place SystemVerilog's model is *not* followed. There
a packed struct is an integral type, so two differently-shaped structs of equal width assign to each
other and silently reinterpret:

```systemverilog
struct packed { logic [3:0] a; logic [11:0] b; } x;
struct packed { logic [7:0] c; logic [7:0] d; } y;
x = y;      // legal SystemVerilog — same width, reinterpreted
```

R14 requires the field names to line up, and it can only do that while the aggregate on the
*writing* side is still an aggregate. Reading gives up nothing by comparison: the bits are defined
either way, and a read that packs cannot lose a field to a neighbour.

### Composable aggregates (R14)

`Vec[T]` is generic over any hardware type including `Bundle`; `Bundle` fields may be `Vec`.
Nesting is arbitrary and unbounded. **Indexing returns `T` with full type information** —
`entries(2).addr` type-checks and yields a `UInt`, never raw `Bits`.

Mechanically this requires a per-element factory rather than a prototype instance, so `Vec(4)(Entry())`
evaluates the constructor once per element.

### Vec lowering (R14.1)

| Context | Form | Reason |
|---|---|---|
| Internal signals | struct-of-arrays: `entries_addr[0:3]` | Verilog-2001 supports net arrays, so `entries_addr[i]` with dynamic `i` is native — **no generated mux tree**, R2 intact |
| Port boundary | per-element: `entries_0_addr`, `entries_1_addr` | Verilog-2001 forbids array ports |
| SystemVerilog backend | real array of packed structs | R9 |

The split is predictable because the boundary is visible in the source: `In`/`Out` marks it, so the
emitted form is readable off the declaration.

### Dynamic-index writes require registers (R14.2)

`vec(i) := x` with a runtime `i` is legal only on a `Reg` Vec, where it lowers to a variable index
inside the `always` block — legal Verilog, no generated logic.

The same write on a combinational Vec is an elaboration error, pointing at either making it a
register or using R14.3.

*Rationale:* Verilog-2001 permits *reading* an array element with a variable index but requires a
constant index for continuous assignment. A dynamic combinational write is a decoder whether or not
one says so; the library makes the user say which.

### Decoder helper (R14.3)

`decode(index, size): Vec[Bool]` returns a one-hot selection. Combinational dynamic-index writes
are expressed as explicit per-element assignments guarded by it.

```scala
val sel = decode(i, depth)
for j <- 0 until depth do
  outs(j) := Mux(sel(j), x, default)
```

The library provides **no** implicit write-decoding combinator. R14.2's intent was not to make
dynamic writes convenient but to make the decoder visible; a one-line `writeDecoded(...)` would
re-hide it with permission. A one-hot vector also composes better — it can drive enables, read
muxes, or valid bits, not only writes.

### Interface (R31)

A named group of ports whose directions are written once, from the master's side. An `Interface`
is to a module's ports what a `Bundle` (R7) is to a signal's fields: a declaration whose fields are
real Scala `val`s, flattened by R7.1, and unknown to the IR. An endpoint *is* its ports, one per
leaf, named `<endpoint>_<field>`.

```scala
/** A memory's client, written from the client's side. */
final case class MemClient() extends Interface:
  val req = Master(Handshake(MemRequest()))   // req_valid out, req_ready in, req_payload_* out
  val rsp = In(MemResponse())                 // rsp_valid, rsp_data in; nothing goes back

final class MacMemPort extends Module:
  val mem = Master(MemClient())               // mem_req_valid, mem_req_ready, mem_req_payload_addr, …

final class Memory extends Module:
  val client = Slave(MemClient())             // the same names, every direction the other way

port.mem <> memory.client                     // every leaf, checked
```

- **Written from the master's side.** Inside the declaration `In` and `Out` are the directions the
  master sees, as `UInt` inside a `Bundle` is a field rather than a wire. `Master(i)` declares the
  ports as written; `Slave(i)` declares the mirror image. Each leaf's direction is therefore still
  one fact readable off the declaration — the property R7 keeps for bundles by refusing per-field
  direction, kept here by fixing the viewpoint instead. A `Bundle` stays direction-free: it is what
  an interface's field carries, not what an interface is.
- **A field is a blueprint and binds to its handle.** A `Type` binds to a signal, a `Bundle` to
  itself with its fields typed, a `Vec` of bundles to its typed handle, an encoding (R24) to a
  signal that carries it. A user's interface never names the pair; a generic one — `Handshake`,
  `Flow` — carries it as `(using Blueprint[P, H])`, which is what lets `payload` come back as the
  bundle when the payload is one. A nested interface is declared with `Master`/`Slave`, and its
  role composes with the endpoint's: a slave's slave reads as written.
- **The field's `val` is the port.** The fields are made while the class body runs, before the
  endpoint has a name; the endpoint declaration then allocates one port per leaf *onto* the handle
  the field already holds, so it is the port's one handle and everything R23 does to a port at
  instantiation — re-pointing an output at the `<instance>_<port>` net, telling an input which pin
  it is — reaches the field. A bundle field is a bundle port, bound as `In(b)`/`Out(b)` bind
  theirs: a parent may read a field of a child's payload (`u.out.payload.addr`) and drive a field
  of a child's (`u.in.payload.addr := expr`), and each is the pin, not a signal of the parent that
  happens to share the child's id.
- **Flattening is R7.1 unchanged**: `mem_req_valid`, `mem_req_payload_addr`. Nothing in the IR or
  the emitter knows an interface exists.
- **An interface may extend another**, which is how a family of related faces declares its shared
  half once. The superclass constructor runs first, so the base's fields come before the
  subclass's, flat and in declaration order — a subclass adds pins to the end of the port list and
  moves none of the ones above it. The two are still separate shapes: the base's is a strict
  *prefix* of the subclass's, never a match, so `<>` between them is the ordinary shapes-differ
  error. A concrete base has to be a plain `class` with an `apply` on its companion rather than a
  `case class`, since a case class cannot extend another.

  ```scala
  class M68kCycle extends Interface:      // what every device on the bus is wired to
    val as, rw, uds, lds = …
  final case class M68kBus() extends M68kCycle:   // …and the answers only some give
    val vpa, vma, e = …
  ```
- **`Master(Vec(n, i))` / `Slave(Vec(n, i))` declare `n` endpoints**, for a module that faces
  several of something — an arbiter, a crossbar, a fan-out. Element *k* binds under
  `<val>_<k>`, which is the name R14.1 already gives a port `Vec`'s elements, and the elements come
  back as an `IndexedSeq`. **This is the one `Vec` that is not a signal**, and it cannot be: an
  endpoint is a group of ports rather than a value, so there is nothing to pack and no runtime
  index. From the binding on, each element is an ordinary endpoint — `<>` connects one exactly as
  it connects any other, and nothing downstream knows they were declared together. A fresh
  interface per element as R14 requires of a `Vec` of bundles, and for a sharper reason: an
  interface may be bound once, so one instance across the elements is an error rather than
  aliasing.

  ```scala
  val client = Slave(Vec(4, MemAccess()))   // client_0_req_valid, … client_3_rsp_payload
  screen.mem <> client(0)
  ```
- **`<>` connects two endpoints of one shape, leaf by leaf, from the side this module reads to the
  side it drives.** In the connecting module every leaf is one of two things: *driven here* — an
  output of its own, or an input of a child — or *read here* — an input of its own, or an output of
  a child. Each pair is connected from the one read here to the one driven here. The roles make
  that determinate, and every sensible pairing comes out right without a case for it: a child's
  master and a child's slave; an own slave forwarded to a child's slave, and a child's master to an
  own master, leaf for leaf; an own slave passed straight through to an own master. An aggregate
  leaf is connected whole, as a bundle port is (R23), so across an instance it is one pin per leaf
  and inside a module a continuous `assign` per leaf (R12.1).
- **Errors**, at elaboration: two endpoints whose shapes differ — names, order, written directions,
  types, nested roles — with both shapes printed; a leaf whose two sides are both driven here or
  both read here, which is what two masters or two slaves on the same footing are; an endpoint of
  a module constructed but never instantiated here, which is R23's foreign-signal hazard on the
  reading side; an endpoint, or a field, not bound to a `val` (R1); one interface instance declared
  as two endpoints.

*Rationale:* R8's endpoint was three ports allocated by hand behind a direction table of its own
and a `<>` of its own, and every other protocol a design had — a request answered by a word that
cannot be refused, a bus cycle answered by a level — was loose ports, wired by hand at every
instantiation with nothing to notice a crossed pair. The construct is the general case R8 was one
instance of, and R8 is now written in it. The connection rule changed on the way: "roles must
differ" allowed an own master to be connected to an own slave inside one module and drove the
input ports, and refused an own slave forwarded to a child's slave, which is the shape every
wrapper has; "from the side read here to the side driven here" gets both right and needs no case
for either.

### An interface states its protocol (R31.1)

R31 writes an interface's **directions** once. It writes nothing about how its leaves may change,
so a protocol's rules live in prose — `MemAccess`'s are three sentences in a comment — and every
endpoint that binds the interface is trusted to have read them.

An interface may state its **protocol** beside its fields. The statements are ordinary expressions
over the fields' handles, and every endpoint that binds the interface emits them as simulation-only
checks (R27), in that endpoint's own clock domain, guarded by the domain's reset. The definition is
where the protocol is written once; the binding is where it is checked, and it is checked in the
module that could violate it rather than in the witness that inherits the fault.

A handshake is the worked example, four properties:

- `valid` is not retracted before `ready`;
- the payload is stable while `valid` stands;
- a response arrives only while a request is outstanding;
- at most one response per request.

The first two are the master's side; the last two belong to a request/response pair such as
`MemClient`. `Handshake` carries the first two as a definition, and a design's own protocol is
stated the same way.

- **The clock is the endpoint's, decided where it is bound.** A port is declared before the
module's clock domain exists (R13.1), so the checks attach at the module's freeze: to the domain
of the region the declaration was written in (R4.1), or, in its absence, to the module's own
domain. The reset guard is that domain's (R13), which is what a concurrent assertion spells
`disable iff (reset)`.
- **A module that names no clock emits no checks.** An endpoint whose domain is the no-clock domain
— an `AddrDecoder` whose whole body is combinational — has no edge to sample, and the protocol is
not checked there rather than checked wrongly.
- **The checks are the design's reading of its own protocol.** They are R27 statements: emitted
inside `// synthesis translate_off`, one block per clock, and removable with the design
bit-identical without them.

*Rationale:* the defects that opened this requirement — a memory port answering `ready` for the
wrong request three times, and a region falling into a switch's default arm — were each found late
and by a machine failing somewhere else. A protocol stated where the interface is declared and
checked where it is bound would have fired at the clock each happened, in the module that did it.

*Future:* the checks may instead be emitted as SystemVerilog concurrent assertions in a sidecar
bound onto the emitting module — which gives the temporal operators directly and can also state the
environment's side as `assume` — if the tools in use check it. R27's immediate checks remain the
form until that is decided.

### Handshake (R8)

Two definitions on R31, in the library rather than the core:

```scala
/** valid/ready/payload, written from the master's side. */
final class Handshake[P, H](p: => P)(using Blueprint[P, H]) extends Interface:
  val valid   = Out(Bool())    // the master has something
  val ready   = In(Bool())     // the slave can take it
  val payload = Out(p)         // what it is: stable from valid until the transfer

/** valid/payload: a word that cannot be refused. */
final class Flow[P, H](p: => P)(using Blueprint[P, H]) extends Interface:
  val valid   = Out(Bool())
  val payload = Out(p)
```

```scala
class Producer extends Module:
  val out = Master(Handshake(UInt(8)))

class Consumer extends Module:
  val in = Slave(Handshake(UInt(8)))

prod.out <> cons.in
```

- **The pairing of the wires is readable off the declaration**: `valid` and `payload` go the
  master's way, `ready` comes back. Nothing about a handshake is special-cased — the roles, the
  names (`out_valid`, `out_ready`, `out_payload`, or `out_payload_addr` for a bundle payload), the
  typed payload and `<>` are R31's, and a design's own protocol is written the same way.
- **`Flow` is the handshake without the `ready`**, for the answers and the samples that are taken
  on the clock they arrive. Declaring one says the slave has no way to refuse, where a handshake
  with a `ready` nobody drives would say the opposite.
- **Standard combinators:** `queue(depth)`, `map`, `fork`, `join`, `throttle`. A cross-domain
  `queue` is R5's `AsyncFifo`, so the CDC story connects; a same-domain `queue(2)` is R30's
  register slice, and a same-domain queue of any depth R32's `SyncFifo`.

*Future:* the handshake contract is R31.1 — an interface states its protocol beside its fields and
every endpoint emits it where it binds, which is `scalahdl.lib.Skid`'s own shape moved into the
definition. The four properties, and the clock each is checked on, are R31.1's; what it waits on is
the same thing: a port is declared before the module's clock domain exists (R13.1), so the checks
attach when the module freezes.

### A register slice (R30)

R8 gives a design the endpoints and leaves the standard components to the library. The smallest
is a two-stage valid/ready pipeline register — a *skid buffer* — that decouples a producer from a
consumer by exactly one beat of slack and a `ready` that flows back when the slack is spent.

```scala
val slice = inst(new Skid(width = 8))
slice.enable := en            // True in a design with no enable
slice.clear  := flush
producer.out <> slice.s
slice.m      <> consumer.in
```

`scalahdl.lib.Skid` is a module, instantiated and connected like any other (R13.2, R23), with four
guarantees a design may rely on:

- `m.valid` and `m.payload` are register outputs: the consumer's logic starts at a flop.
- `s.ready` is a flop read — the slot's valid, inverted — with **no combinational path from
  `m.ready`**; there is no ready chain through the slice.
- A beat accepted while the output is stalled is held in the slot and leaves first.
- `clear` drops both stages; `empty` says nothing is in flight; `enable` gates every update, so
  a producer whose pulses are one enable wide is sampled once.

The reset *shape* is the constructor's — `Skid(8)`, `Skid(8, ResetKind.Sync, ActiveHigh)` — because
a component imported into somebody else's design resets the way that design does. It names the
module only when it is not the default (R16), so the common case stays `Skid_w8`.

A same-domain `queue(2)` (R8) is this module. A cross-domain queue is R5's `AsyncFifo`, as before.

*Rationale:* R8's endpoints had nothing built on them until a design needed a slice with an
enable, which no generic FIFO provides and no design should write twice. `lib` holds components
a design imports, distinct from `examples/`, which demonstrate.

### A synchronous FIFO (R32)

R30's slice holds two words. A queue deeper than that, in one clock domain, is the second
component: a first-in, first-out buffer of `depth` words between two R8 endpoints.

```scala
val keys = inst(new SyncFifo(depth = 8, width = 10))
producer.out <> keys.s
keys.m       <> consumer.in
```

```
         ready = not full                            valid = not empty
  s ─ valid, payload ──► words[tail]  …  words[head] ──► payload ─ m
    ◄── ready                                               ready ──►

  tail, head: positions one bit wider than an address, so full and empty differ
```

`scalahdl.lib.SyncFifo` is a module like any other (R13.2, R23), with these guarantees:

- `s.ready` is *not full*, read from the two positions only: there is no combinational path from
  `m.ready`, and a full queue refuses a word even on the clock one leaves. A line of queues has
  no ready chain through it.
- `m.valid` is *not empty*, from the positions only, and `m.payload` is the word at `head`: a word
  taken on one clock is offered from the next, and nothing passes from `s` to `m` in one.
- Order is kept, and no word is lost or offered twice.
- The depth is a power of two, 2 or more, so a position wraps by overflowing.
- The words are held in the memory kind the design names — `Registers` by default, or `LutRam`
  (R15) — both read on the clock they are addressed. A `BlockRam` is read a clock late, and a
  queue on one is another structure: it is refused at construction.

The depth and width always name the module, `SyncFifo_d8_w10`; the storage names it when it is not
`Registers`, and the reset shape when it is not the library default, as R30's does.

A same-domain `queue(depth)` (R8) is this module for any depth, and R30's `Skid` where the two
words must leave from registers. A cross-domain queue is R5's `AsyncFifo`.

*Rationale:* a design asked for one: a keyboard holds the key transitions a PS/2 port
delivers until the processor collects them, a few words deep.

### Named encodings (R24)

An operation selector, an FSM state, a bus response code: each is a small set of named constants
sharing one width, and each is otherwise written as a plain `Int` promoted at every use —
`AluOp.Or.U(AluOp.Width)` ten times in one `switch`, and again in the decoder that drives it. R6.1
removes half of that, since a case label takes its width from the selector. It cannot remove the
other half: literal inference stops at an assignment boundary by design, so `decodedOp :=
AluOp.Add.U(AluOp.Width)` keeps its annotation, and so does each arm of a `Mux` between two members.

R24 declares the set once and gives its members their width:

```scala
object AluOp extends Encoding(4):
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And = Value

class Alu extends Module:
  val operation = In(AluOp())

  switch(operation) {
    is(AluOp.Add) { result := operandA + operandB }
    is(AluOp.Or)  { result := operandA | operandB }
    default       { result := 0.U(32) }
  }
```

- **A member is a constant carrying its own width**, so it needs no annotation in any position a
  literal would need one: `is(AluOp.Or)`, `op := AluOp.Add`, `Mux(alt, AluOp.Sub, AluOp.Add)`,
  `op === AluOp.And`. This is not a relaxation of R6.1's assignment-boundary guard — there is
  nothing left to infer. Width disagreement stays R6's error, unchanged.
- **Codes count from zero in declaration order.** `Value(n)` pins one and counting resumes at
  `n + 1`, which is what an encoding fixed by an external document — a `funct3` field, an AXI
  response — actually needs.
- **Member names come from R1**: the same `spliceOwner` capture that names a port. `val Add, Sub =
  Value` is two `val`s and therefore two names, exactly as `val a, b = In(UInt(8))` is two ports
  under R10.
- **`Encoding(w)` pins the width; `Encoding()` takes the minimum that holds the largest code**, and
  never less than one bit. An encoding crossing a port is normally pinned, because there the width
  is part of the interface rather than a consequence of how many members it has today.
- **`AluOp()` is the type** for a port, a wire, or a register: `In(AluOp())`, `Wire(AluOp())`,
  `Reg(AluOp(), init = AluOp.Add)`.
- **The host side keeps both the number and the name.** `AluOp.Or.code` is `8`, `AluOp.Or.name` is
  `"Or"`, and `AluOp.values` lists the members in declaration order — what a test oracle or a
  generator loop needs, and the reason a member is not simply an `Expr`.

**Not a new hardware type.** An encoding is a surface construct over `UInt`: `AluOp()` *is*
`UInt(4)`, and `AluOp.Or` *is* the four-bit literal `8`. Emitted Verilog is byte-identical to
writing the number, so R12 and R20.1 are untouched, and two encodings of the same width remain
interchangeable as far as the IR is concerned. What R24 does not claim is thereby explicit: it is
not a type-level guarantee that an `AluOp` cannot be confused with a `Funct3`. That version teaches
a new `Type` to every validation phase and to the emitter, and is deliberately not part of this
requirement — R24 is the removal of a repeated width, not a new type system.

**Names reach the diagnostics, never the Verilog.** A signal declared with an encoding records it,
so the R11/R22 messages can name the member: a switch that misses an alternative says
`8 (AluOp.Or)` rather than `8`. No `localparam` is emitted — the encoding is a source-level fact,
and R12's promise is that what is emitted is what was written.

**Completeness is unchanged.** A `switch` on an encoded selector is exhaustive under R11/R22 on the
same terms as any other: a `default`, or alternatives covering the selector's whole value space.
Naming ten of sixteen encodings does not make the remaining six unreachable — the hardware can
present them — so an encoding is not a licence to drop the `default` arm.

**Errors.** Three, each its own R19 code:

- A **pinned code that does not fit a pinned width** — `Value(16)` in an `Encoding(4)` — naming the
  width it needs and the width it has, as R21.7 already does for a literal.
- **Two members sharing a code**, naming both. The later one is otherwise a silent alias, and the
  `switch` that eventually contains both reports a duplicate-value error far from its cause.
- **A member declared after the width has been read.** Only reachable when the declaration is
  something other than a plain object body, and an error because the alternative is a width whose
  value depends on when it was first asked for.

*Rationale:* the alternative costing nothing is to declare the members as `Expr`s directly (`val
Add = 0.U(4)`), which is what R22's own FSM example does for four states and is entirely adequate
there. It stops scaling once the set becomes an interface: the codes are written twice if the host
side also needs them, adding a member means renumbering by hand, and no diagnostic can say
`AluOp.Or` because nothing recorded the name. R24 is that idiom with the counter, the name capture,
and the width folded into one declaration.

### A register map (R40)

A register block is one declaration. It states the block's base address, its word
width and its address width. Each register states its word offset. Each field
states its two ends.

An address counts bytes. A register offset counts words. One word occupies
`wordBits / 8` bytes, so the address of word `offset` is
`base + offset * (wordBits / 8)`. `bytesPerWord` is that count, derived from the
width the map already states. A word width that is not a whole number of bytes is
refused when the map is built.

```scala
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
```

**A register states its extent, and may repeat.** `Register(offset, words,
count, stride)` defaults to one word, one occurrence, stride one. `words` is how
many words the register occupies from `offset`; `count` and `stride` declare a
register that repeats, where slot `k` starts at `offset + k * stride`. The clash
check compares occupied words, so a register spanning eight words holds eight.
`wordsUsed` reports them, and `at(index)` gives a repetition's offset in an `is`
label.

```scala
    object Resp    extends Register(8, words = 8)    // 8 words from word 8
    object Mailbox extends Register(8, count = 4, stride = 2)   // words 8, 10, 12, 14
```

**A register takes no map.** A map lists its registers, so a register that asked
the map for a `given` would start the map's body while its own was still running.
Code outside a map names a register first, and that order would then read a
register that is still null. A register therefore states nothing that needs the
map, and each check that needs both runs in `registers(...)`.

**A coded field is declared where R24 declares an encoding: as its own `object`
in the map.** A nested `object` inside a register would be lazy, so its body
would not run while the register was built, and the register would hold a field
that was never created. An `object` in the map's body is built with the map.

**The register names it, and that name is the field's.** `val Op = add(OpCodes)`
records the field under `Op`, so the header says `UART_COMMAND_OP_SEND` whatever
the codes object is called. A coded field that no register passes to `add` is
refused when the map is listed, so a forgotten `add` is an error rather than a
silently absent name.

**Three consumers read one declaration.** A serving module reads a field through
its handle. The map renders a C header for firmware. The map renders a
SystemVerilog package for a testbench. Both renders read the same objects, so
neither can disagree with the module or with the other.

```scala
   UartRegs.Command.Index.of(bus.writeData)   // the part-select, six bits wide
   is(UartRegs.Command)                       // the word offset, as a constant
```

Before this rule the module writes `bus.writeData(9, 4)` and the header writes
`UART_COMMAND_INDEX_SHIFT 4u`. Both numbers are the field's, written twice. A
register that moves in the module gives firmware a stale address, and no build
step compares the copies.

**A field's facts are its low bit and its width.** The handle gives `of(signal)`,
`lo`, `hi`, `width` and `mask`. `of` is R34's runtime window when its argument is
not a constant, and R6.7's constant window otherwise.

**A field of named codes is an encoding** (R24). Its members stand in every
operand position, and its diagnostics name members. This rule adds no mechanism
for codes.

**A coded field is declared once and taken by name.** The codes are an `object`
in the map, because a nested `object` inside a register is lazy and its body
would not run while the register was built. The register takes the field as
`val Op = add(OpCodes)`, and that `val`'s name is the field's name in both
renders. A register that holds a coded field it never took is refused
(`REGMAP_FIELD_UNTAKEN`), so a forgotten `add` is a diagnostic rather than an
absent name.

**No hardware comes from a map.** A map declares no signal and no statement, and
it declares no register in the R13 sense. It emits no Verilog, and the IR gains
no node. A module that reads a field through a handle emits the same bytes as the
module that writes the bit pair by hand.

**A register stands in an `is` label as its word offset.** One conversion does
it, declared beside the register factories, and it satisfies R22's requirement
that a label be an elaboration-time constant.

**Both names are required, with no default.** `prefix` is the C name prefix and
`pkg` is the SystemVerilog package name. Neither language gives a map a scope of
its own, and a default would make two maps collide silently.

**The C header renders by eight stated rules.** The name is `prefix`, the
register's upper-case name, then `_` and the field's. A name the declaration
pinned through `cName` is used exactly as pinned, because it is already a C name.
A bit field is `(1u << n)`. A coded field writes one define per member, named
`prefix`, register, field and code. A field that is wider than one bit writes
`_SHIFT` and `_MASK`. The address is `base + offset * bytesPerWord`, as eight hex
digits with a `u` suffix. The name column is 26 characters. The file opens by
refusing a second map of its prefix.

**A code can pin its number.** `code(at)` states a member's code and counting
resumes above it, for a code an external document fixes. `code(at, cName)` pins
its C name as well. `code` with no argument still counts from zero.

```
#ifndef UART_REGS_H
#define UART_REGS_H

#ifdef UART_REGS_DEFINED
#error "a second register map uses the prefix UART_"
#endif
#define UART_REGS_DEFINED 1

#define UART_CONTROL               0x10000000u
#define UART_CONTROL_ENABLE        (1u << 8)
#define UART_COMMAND_INDEX_SHIFT   4u
#define UART_COMMAND_OP_SEND       0u

#endif
```

**Every `#define` starts with `prefix`**, including a code's. A C macro has no
scope: it reaches every file after its `#include`, with no way to qualify it.
Only the prefix makes a name unique, so a code named `Send` in two registers
stays two names because the register's name is in the path.

**The prefix sentinel makes a second map of one prefix a compile error.** C
requires a diagnostic for a redefinition that differs and permits one that does
not, so a repeated `#define` is a warning at best and silent at worst. The
sentinel is `#error`, which stops the build under every compiler with no flags.

Because every name carries the prefix, two headers collide only by sharing a
prefix, so one sentinel per header covers the whole file. It catches the case
the generator cannot: two maps written by two separate runs.

**The SystemVerilog render is a package.** A bench indexes a word by offset and
slices a field by its low bit and its width, so the package states those and no
mask.

```systemverilog
package uart_regs;
    localparam logic [3:0] CONTROL_ADDRESS = 4'h0;       // the word offset
    localparam int unsigned CONTROL_DIVIDER_SHIFT = 0;   // a field's low bit
    localparam int unsigned CONTROL_DIVIDER_WIDTH = 8;   // ...and its width
    localparam logic [1:0] COMMAND_OP_SEND = 2'd0;       // a code, at the field's width
endpackage
```

A package is a real scope, so two maps may hold one member name and a bench says
`uart_regs::CONTROL_ADDRESS` or imports one package at a time. Inside the
package the member name starts with the register's upper-case name, so two
registers of one map never collide, and a coded field's member carries the
field's name as well.

A bench reads a field as
`got[uart_regs::CONTROL_DIVIDER_SHIFT +: uart_regs::CONTROL_DIVIDER_WIDTH]`,
which is the sliced form R34 already emits.

**A map renders its body apart from its wrapper.** `cDefines` is the `#define`
lines a map owns. `cHeader` is that text with a guard and a sentinel around it. A
design that owns several maps writes their bodies into one file inside one
guard, as `CompanionLayout.header` does, and the non-register defines stay in the
design.

**A pinned C name stops at the header.** A package names its members from the
declaration, because a bench reads a scope rather than a C macro, and a second
naming of one field would be one fact stated twice.

**One call writes every map of a design, and it compares their names.** A map
cannot see its siblings, so the check lives where the maps are already named
together: the call that writes their files.

```scala
    RegisterMap.write(dir, Seq(UartRegs, SpiRegs))
```

That call writes one `.h` and one `.sv` per map, and refuses two maps that share
a `prefix` or a `pkg`. The list is not a separate register of maps: it is the
argument the write already needs, so a map it omits has no file and fails at its
first `#include`. A global registry would catch more and would break R3.

**The decode stays the module's.** Each register behaves differently: a
read-only status word, a write-only command with its own inner `switch`, a word
that reads in eight slices. A decode helper would need one body per register,
which is a separate construct with its own requirement.

**`registers(...)` is the membership list and the forcing point.** Passing an
object as an argument runs that object's body, which is what adds its fields to
the header. A register that no call names is absent from the header; the cost of
that is paid for by the compiler checking every name in the list.

**Errors.** Seven, each its own R19 code. The first six fire while the map object
initialises, because that is the first moment the whole block exists; five of
them fire in `registers(...)`, where a register and the map meet. The seventh
fires when the maps of a design are written together.

- An **offset that needs more bits than the address width**, measured at the
  highest word a register occupies.
- **Two named registers on one word**, naming both, whether the word is a
  register's own or one its extent covers.
- A **field whose high bit is above the word's width**, reported at the field's
  own declaration.
- **Two fields of one register that share a bit**, naming both.
- A **field or a code that is not bound to a `val`**, so it has no name.
- A **coded field that a register holds without `add`**, so it would reach no
  render and report nothing.
- **Two maps of one design that share a `prefix` or a `pkg`**, naming both.

The same clash outside one design is the header's own `#error`, because the
generator sees only the maps it was given.

*Rationale:* this is R24's bargain again. A surface declaration removes a
repetition and records a fact, and it teaches no new node to any validation phase
or to the emitter. The fact it records is larger, because three files need it
rather than one.

---

## 6. Ports and logic

### Module ports (R10)

Declared directly in the module body with `In(...)` / `Out(...)`. No `io` wrapper.

```scala
class Adder extends Module:
  val a, b = In(UInt(8))
  val sum  = Out(UInt(9))
```

Verilog port names are exactly the `val` names — no prefix to strip, which suits R1. Submodule
access is `inst.a`. R1's collision rule applies across ports and internal signals alike.

The `io` grouping lost its purpose once R7 removed per-field direction. Ports that belong together
are grouped by an `Interface` (R31), which names them `<endpoint>_<field>` and keeps each direction
one fact.

### Combinational completeness (R11)

A combinational signal must be driven on every path. Incomplete assignment is an elaboration error
naming the signal, the uncovered condition, and the declaration line. The fix is an explicit
default before the conditional, since assignments are last-wins in source order.

### Registers exempt (R11.1)

An unassigned path on a `Reg` means "hold value", which is what a register does. No error, no
default required.

*This asymmetry is the teaching point:* the same `when` block means different things for a wire and
a register, and the library makes the wire case loud rather than silently inferring a latch.

### A combinational cycle is an elaboration error (R28)

A combinational value must **settle**. A design in which one is computed from itself, directly or
around a ring of wires, is refused at elaboration, naming the ring in the source that closed it.

```scala
val a = Wire(UInt(8))
val b = Wire(UInt(8))
a := b + d
b := a          // error[COMBINATIONAL_CYCLE]: `a` is computed from itself: a -> b -> a
```

*This is R11 one step along, and the pairing is the teaching point:* R11 asks whether a wire is
driven on every path; R28 asks whether what drives it settles. R11.1's exemption is exactly why
`y := y + 1` stays legal on the declaration one line up — on a register that is a counter, and only
on a wire is it a ring.

R17.1's claim is a different one and both hold. The **object** graph is acyclic by construction: a
driver block references its target and a signal never references its block. The **dependency**
graph — whose edges read *this value is needed to compute that one* — is derived, is not acyclic by
construction, and is what a circuit either settles on or does not.

**The graph is leaf-granular, and that is exact rather than cautious.** R12 gives a bundle wire one
driver block, so `r.addr := r.data + 1` would be a self-edge on a signal-level graph and is no ring
in any circuit. Below the leaf there is nothing to miss: R6.7 requires a register for a part-select
target and R14.2 requires one for a runtime-indexed element, so every write to a combinational leaf
writes the whole leaf. Reads stay conservative the other way — a runtime index reads every element
— and that over-approximation does not over-report, because a wire really can close a ring for the
one value of the index that names it.

**An instance is edges, not an opaque box.** Each module is summarised for its parent by which of
its inputs reach which of its outputs combinationally; children elaborate first, so the answer
exists when the parent needs it. A ring closed through a child is found; the same shape through a
child that registers its input is not a ring and is not reported.

**A blackbox declares what cannot be known about it.** `combPath(in, out)` states that an external
module carries a combinational path between two of its ports, exactly as R18.2 has it state its
domains and R18.5 its unpacked ports. A box that declares none is *assumed* to carry none — the
permissive default, because the strict one would refuse every design containing a registered
peripheral in a feedback path — and the assumption is printed in R29's report rather than left to
be discovered.

*Rationale:* the rings are caught today, by Verilog lint, in terms of emitted wire names and
`ASSIGNW` nodes, after a gate a project has to remember to run. Every hop of the ring was written
by a named `val` (R1) inside a statement carrying a `Loc` (R17), and the library is the only thing
that holds both.

### Literal emission (R12)

Conditional assignment emits as written: `always @(*)` with blocking assignments for combinational
logic, `always @(posedge clk)` with non-blocking for registers. **Nesting depth, branch order, and
statement order in the output match the source.** No collapsing into ternaries, no reordering, no
merging of separate blocks.

```scala
val y = UInt(8)
y := 0
when(sel) { y := a }
```

```verilog
always @(*) begin
  y = 8'd0;
  if (sel) begin
    y = a;
  end
end
```

The collapsed alternative (`assign y = sel ? a : 8'd0;`) is more compact for trivial cases but a
five-level `when` becomes an unreadable nested ternary and correspondence to source is lost.

**One signal, one driver block:** all assignments to a signal collect into the single `always`
block created at its declaration, in source order. Two separate `when` blocks driving one signal
would be illegal Verilog; this preserves literalness while staying legal.

### Unconditional combinational assignments are continuous `assign`s (R12.1)

A combinational driver block whose whole content is **unconditional assignments, each to a whole
leaf and each leaf once**, emits as continuous assignments, and its target is declared `wire`
rather than `reg`:

```scala
val kbdPeriod = Wire(UInt(11))
kbdPeriod := 1300.U(11)
```

```verilog
wire [10:0] kbdPeriod;
assign kbdPeriod = 11'd1300;
```

**This is R12 applied more closely, not relaxed.** R12's worked example above argues against
collapsing a `when` nest into `assign y = sel ? a : 8'd0;`, and that argument is about *flattening
structure the source wrote*. Here there is no structure to flatten: the source wrote one assignment
and no condition, and `assign` is that assignment with nothing around it. Nothing is reordered,
merged, or turned into a ternary — the three lines that go are `always @(*) begin`, `end`, and the
indentation between them.

**And the procedural form is wrong when the expression reads nothing.** `always @(*) begin x =
11'd1300; end` has an empty sensitivity list: IEEE 1800 §9.4.2.2 builds the list from the variables
the block reads, a block with no list has no event that can trigger it, and the target keeps its
initial value — `x` — for the whole simulation. Verilator reports it (`ALWNEVER`, plus `UNDRIVEN`
on the target), so it is detectable; under this rule it is **unrepresentable**, because a continuous
assignment holds from time zero whatever it reads.

That case is not exotic and does not look like a mistake in the source. Deleting a machine-type
strap in a design turned `Mux(isSe, 80.U(11), 1300.U(11))` into a literal and a legitimate
`Wire` into an illegal one; the edit that caused it was the deletion of a term.

**The conditions, and why each is needed:**

| condition | why |
|---|---|
| combinational | a clocked block is an edge and a reset branch (R13); there is no continuous form |
| no conditions | a `when` is a condition and a `switch` is a partition; both are sequential by nature |
| a whole leaf each | a write to a *part* (`count(7, 0) := d`, R6.7) would leave the rest of the net undriven rather than holding, which is a different circuit |
| each leaf once | two writes to one leaf are a sequence, where two continuous assignments would be two drivers of one net |

**An aggregate qualifies, and that is the case this rule is usually met in.** R07.1 expands it to
one line per leaf either way, so a driver that assigns several leaves of one bundle — which is what
an interface payload is, every time — is the same case as a scalar's single assignment. The rule
must therefore count leaves, not statements: a statement-shaped rule would accept an aggregate's
driver, whose block reads nothing and so never runs.

**What cannot be continuous still has to be reachable.** A combinational block that keeps its
procedural form and reads nothing has no event that can trigger it, and that is
`COMBINATIONAL_NEVER_RUNS` at elaboration, naming the signal. After this rule the only shape that
gets there is a condition on a constant — a part-select cannot, because R6.7 refuses a partial write
in a combinational block before this rule is asked.

**A signal is a `wire` or a `reg` by how it is driven, not by what it is.** A continuous assignment
may not target a `reg`, so this rule decides the declaration as well as the block: a combinational
signal that used to be `reg` because *something* assigned it procedurally is now `wire` when that
something is an `assign`. The same applies at a port — `output reg` becomes `output`.

### Every branch of an `if` is a `begin`/`end` block (R12.2)

Every branch of every `if` the emitter writes — a `when`, a register's reset, a check (R27), a
memory port's enable (R15.5, R15.6) — is a `begin`/`end` block, whether it holds one statement or
none. An `else` whose whole body is one conditional is written `end else if (…) begin`, the next
link of the chain, at the chain's column. Each level of nesting is two columns, and `if` and `case`
sit at the column of the statements around them.

```scala
val compare = Reg(UInt(8), init = divider)
when(count === compare) { compare := divider }
.elsewhen(count === 0.U(8)) { compare := divider }
```

```verilog
  always @(posedge clk) begin
    if (module_reset) begin
      compare <= divider;
    end else if ((count == compare)) begin
      compare <= divider;
    end else if ((count == 8'd0)) begin
      compare <= divider;
    end
  end
```

**What it prevents.** Verilog binds an `else` to the *nearest* unmatched `if`, so written bare,

```verilog
if (a) if (c) y = p;
else y = q;
```

gives `y = q` on `a && !c` and nothing on `!a` — a `when` nested in a then-arm, read back out of
the text as a different nesting. And a statement that becomes several in Verilog — an aggregate
assignment, one line per leaf (R07.1) — written bare after an `if` leaves every leaf but the first
unconditional. Braces placed only where the ambiguity was prevented both, but the emitter had to
decide each case, and the first of these defects shipped: it stopped every taken branch of a
68000 core from re-aiming the instruction stream. With every branch closed by its own `end`, there
is no case to decide.

**`else if` is the chain, not an exception.** In Verilog `else if (c)` is an `else` whose one
statement is an `if`; written flat it states exactly the nesting R12 requires, and a priority chain
reads as the list it is rather than as a staircase.

**A `case` arm is not a branch of an `if`.** No `else` can follow it, so an arm with one statement
stays on its label's line (`3'd1: y = a;`), and its continuation lines — a nested `if`'s `end`, a
nested `endcase` — close at the arm's column. An arm with several statements, or with one that
becomes several, is a block, as before; an empty arm is `;` (R22.1).

**What it costs** is lines: a branch of one statement takes two or three instead of one, which on
a design of 61 modules is one line in six more (22,953 to 26,664).

### First-class switch/case control flow (R22)

A multi-way partition of one selector is expressed directly, rather than as repeated equality-tested
`when` blocks:

```scala
switch(state) {
  is(stateReset) {
    state := stateRequest
  }
  is(stateRequest) {
    state := stateWait
    when(mem_ready) {
      instruction := mem_rdata
      state := stateDecode
    }
  }
  is(stateWait) {
    when(mem_ready) {
      instruction := mem_rdata
      state := stateDecode
    }
  }
  is(stateDecode) {
    pc := pc + 4.U(32)
    state := stateRequest
  }
}
```

`switch(selector)` accepts one bit-typed expression. Each `is(v1, v2, ...)` has a non-empty list of
aliases and one shared body. Every value must be an elaboration-time constant expression with the
selector's kind and width after R6.1/R21.7 local literal resolution; there is no implicit resize or
signedness conversion. `default` is optional and may appear at most once.

The switch body is an elaboration scope, not a closed host-language data structure. Ordinary Scala
producers may enlist alternatives by calling `is` while that scope is open:

```scala
switch(opcode) {
  for (entry <- decodeEntries) {
    is(entry.opcode) {
      result := entry.emit()
    }
  }
  is(opAdd, opSub) {
    arithmetic := true
  }
  default {
    illegal := true
  }
}
```

`for`, `foreach`, iterators, and helper methods are equivalent to writing their resulting `is` calls
by hand. Alternatives and aliases preserve the order in which elaboration produces them. The host
producer itself does not enter the hardware IR.

**Duplicates and overlap:** R22 initially admits exact constant values only, so overlap is precisely
duplicate canonical identity: selector kind + width + represented bit pattern. Duplicate checking
runs after the whole switch body has elaborated and therefore spans aliases, handwritten arms, and
arms produced by independent iterators. The diagnostic points at the later value and relates the
first. Empty `is()`, non-constant or selector-incompatible values, a second default, and `is` or
`default` outside an open switch are R19 errors. Masks, ranges, wildcards, and arbitrary Boolean
patterns are not part of R22.

**Completeness:** a default arm is exhaustive. Without one, a switch is exhaustive only when its
distinct canonical values cover the selector's entire finite value space. A combinational target
must be assigned in every reachable arm unless an earlier statement in its driver block already
covered it — normally the R11 default-before-conditional idiom. A non-exhaustive switch leaves an
uncovered path. Registers retain R11.1 semantics: an uncovered value means hold. Full-coverage
recognition is exact but must not enumerate a large value space merely to count it.

**IR and emission:** a switch is a first-class statement containing the selector, an ordered list of
case records (each with an ordered non-empty value list and ordered statements), an optional default
statement list, source locations, and nested statements. It is not lowered to nested conditionals.
The Verilog-2001 backend emits one native `case (selector)`, comma-separated labels for aliases, and
`default` only when present. Case, alias, nesting, and statement order match elaboration exactly;
there is no sorting, merging, conversion to `if`, or ternary collapse (R12/R20.1). R22.1 states the
one thing a driver block does not print: an alternative that this signal's projection left empty.

### A projected case arm that writes nothing is not emitted (R22.1)

One `switch` may be shared between many signals, and R12 gives each of them its own driver block.
The IR therefore holds one *projection* of the switch per signal written, carrying every
alternative, with a body only where that signal is assigned — R11 reads exactly that to decide
whether an arm leaves a combinational target uncovered, so the arms stay in the IR unconditionally.

Emission does not print them. An alternative with no statements **in this projection** is dropped,
and where dropping one would leave the case covering fewer values than the source did, a
`default: ;` takes its place:

```verilog
always @(posedge clk) begin
  if (!resetn) begin
    latchedAluOp <= 4'd0;
  end else begin
    case (state)
      3'd3: latchedAluOp <= decodedAluOp;
      default: ;
    endcase
  end
end
```

The pruned arms did nothing and the default does nothing, so this is the same hardware as the full
enumeration and the case remains fully specified — a partially listed case with no default reads as
an oversight and is what a lint tool reports (Verilator `CASEINCOMPLETE`).

**Pruning is off entirely when the projected `default` assigns something.** There an empty arm is
not silence but *shadowing*: it is what keeps its values out of the default's statements, and
dropping it would route them into assignments they must not run. In
`switch(s) { is(0) { x := a }; is(1) { y := b }; default { x := c } }` the projection of `x` keeps
`is(1)` empty, because `s == 1` must not assign `x` at all.

Nothing else about R22's emission changes: alternative order, alias order, nesting, and statement
order still match the IR, arms are never merged or reordered, and a `default` the source wrote is
emitted as written and never duplicated. The synthesized `default: ;` is not a new statement — it
is the arms just removed, spelled once.

*Rationale:* the alternative list a driver block sees is structure the projection carried in, not
something the source wrote about this signal. On an eight-state FSM that latches a dozen registers,
printing it verbatim gives every register a case whose one real arm is buried under seven empty
ones, and the reader has to compare labels across blocks to find it. R12's "no rewriting" governs
what the source *said*; it is not an obligation to print what projection *added*.

### Selection on conditions: `priorityCase` and `parallelCase` (R26)

R22 admits exact constant labels only, and says so outright: *"Masks, ranges, wildcards, and
arbitrary Boolean patterns are not part of R22."* R26 is the other half of that sentence. A decoder
whose alternatives are **ternary matches** over an opcode word has no constant to label an arm with
— every alternative *is* a condition — and writing eight hundred of them as a `when` chain buries
the partition in eight hundred nested `else`s.

```scala
priorityCase {
  on(kind === opLoad)  { width := 2.U(2) }
  on(kind === opStore) { width := 1.U(2) }
  default              { width := 0.U(2) }
}

parallelCase {
  for cube <- cubes do
    on((opcode & cube.mask.U(16)) === cube.value.U(16)) { shape := cube.shape.U(9) }
  default { shape := 0.U(9) }
}
```

Both emit Verilog's `case (1'b1)`: the selector is the constant one, and each arm's label is its own
condition.

```verilog
case (1'b1)
  ((opcode & 16'hf1f0) == 16'h7000): shape = 9'd0;
  ((opcode & 16'hffc0) == 16'h4ec0): shape = 9'd41;
  default:                           shape = 9'd0;
endcase
```

`on(c1, c2, ...)` takes one or more one-bit conditions sharing one body, exactly as `is` takes
values, and emits them as comma-separated labels meaning *any of these*. A condition is an ordinary
expression and is **not** required to be an elaboration-time constant — that is the entire point —
but each must be one bit wide, which is the only property `case (1'b1)` can compare against. The
body is an elaboration scope on R22's terms: a `for`, an iterator or a helper may call `on` while it
is open, and the result is what writing those calls out by hand would produce.

**The two constructs differ in what they claim, not in what they emit.** `priorityCase` claims
nothing: the first arm that holds wins, which is Verilog's own reading of `case` and exactly the
`when`/`.otherwise` chain it replaces. `parallelCase` claims that **at most one arm holds**, which
is a fact about the design that the emitted structure cannot state and a synthesiser cannot recover.

**R26 decides that claim rather than recording it.** Every `parallelCase` arm must be a *cube*: a
masked compare against one selector expression common to the whole construct, written
`(sel & m) === v`, or `sel === v` where the mask is every bit. An arm R26 cannot read that way is an
R19 error naming it, so the claim is never quietly unchecked and `parallelCase` never degrades into
a promise. Given cubes, overlap is arithmetic — two intersect exactly when
`(v₁ ^ v₂) & m₁ & m₂ == 0` — and an overlapping pair is an error naming both arms and one selector
value that reaches them both. `priorityCase` accepts any one-bit condition precisely because it
claims nothing about them.

**Completeness:** a `priorityCase` or `parallelCase` is exhaustive only with a `default`. R22 can
call a switch exhaustive when its labels cover the selector's finite value space; conditions have no
such space to cover, and "these eight hundred cubes partition the sixteen-bit surface" is a fact
about the *design* rather than about the construct. R11's uncovered-path verdict and R11.1's
register-hold semantics are otherwise unchanged.

**IR and emission:** not a new statement. A `Switch` gains a selection *kind* — R22's `Values`, or
R26's `Priority` and `OneHot` — and R26's forms carry the constant one as their selector and one
condition per label. Everything already written against `Switch` therefore applies unchanged: R12
projects it per driver block, R22.1 prunes the arms a projection left empty, R11 reads every
alternative from the IR rather than from what was printed, and R20.1's ordering rules hold. The
Verilog-2001 backend emits `case (1'b1)` with the arms' conditions as labels. `parallelCase` also
emits `parallel_case`, which is R26.1 below; `priorityCase` claims nothing and emits nothing.

*Rationale:* the alternative is a `when` chain, which is semantically identical and which the
emitter already produces as `if / else if / else`. Three things are lost in it. The partition is not
visible — a hundred alternatives read as a hundred nested conditionals, and R22 exists because that
was true of four. The claim that alternatives are disjoint has nowhere to live, so it is either
unstated or stated in a comment. And the shape a synthesiser sees is the one thing here that is not
a matter of taste: `casez` asks it to infer a mux from don't-care patterns, `case (1'b1)` hands it
the comparisons already written. R26 is the construct that says all three.

### A decided claim is emitted, because the synthesiser cannot see the proof (R26.1)

R26 as written refused the synthesis attribute, and the reason it gave was that *"`parallel_case`
tells the tool to assume what R26 has already proved, and a proof that has been checked does not
need to be asserted as well."* That is true of a reader and false of a tool. The proof is made in
the elaborator and consumed there; nothing of it reaches the Verilog. A synthesiser handed
`case (1'b1)` has one reading available to it, the one the language defines — evaluate the arms in
order, take the first that holds — and it must build the hardware that reading describes.

**So a `parallelCase` emits `case (1'b1) // synopsys parallel_case`, and a `priorityCase` does not.**

*What it cost to not do this.* plustoo-soc's 68000 decodes its instruction word through a
`parallelCase` of **267 arms** — one per instruction shape, cubes proved disjoint by R26 at
elaboration. Quartus built the 267-deep priority chain the language asked for: about **146 LUT
levels in series**, **92 ns of a 98 ns critical path**, and an `Fmax` of **10.18 MHz** on a
`5CEBA4F23C7` at slow 85 °C. The same design with the attribute, nothing else changed, fits in
**2,838 ALMs against 3,334** and makes **33.23 MHz** — 3.26x the clock on 15% less area, from a
comment. The critical path moves to the microcode store and the design's own logic-level report
becomes predictive again, having ranked the decoder at a depth that assumed a balanced tree.

*Why it is sound here and is not in general.* `parallel_case` is rightly distrusted: it instructs a
tool to skip priority, so a design whose arms overlap synthesises into something its own simulation
disagrees with, silently. That failure needs arms whose disjointness was *asserted*. R26's are
*decided* — every arm a cube over one common selector, overlap settled by
`(v₁ ^ v₂) & m₁ & m₂ == 0`, an overlapping pair an R19 error naming both arms and a witness value
that reaches them both. A design the attribute would miscompile does not elaborate, so the
attribute cannot be reached by one. This is the payment R26 collects for refusing arms it cannot
read, and R26.1 is where it is spent.

*The spelling.* `// synopsys parallel_case`, in a comment rather than an attribute, and the
historical vendor prefix rather than the accurate one: it is what Quartus, Vivado, Synplify and
Yosys all honour, and a Verilog-2001 backend that emitted SystemVerilog's `unique case` instead
would be changing the language it claims to write.

### A state machine is one declaration (R25)

A multicycle machine is three things the source states separately: a state register, a `switch` over
it, and the assignments that move it. Nothing in the text says they are one object, so the machine's
*graph* — which state reaches which, under what condition — exists only in the reader's head, and
the tools have no access to it at all. R25 makes the machine one declaration:

```scala
object State extends Encoding(4):
  val Reset, Request, Wait, Decode, Trap = Value

val state = Fsm(State, init = State.Reset) {
  in(State.Reset) {
    goto(State.Request)
  }
  in(State.Request) {
    goto(State.Wait)
    when(mem_ready) {
      instruction := mem_rdata
      goto(State.Decode)
    }
  }
  in(State.Wait) {
    when(mem_ready) {
      instruction := mem_rdata
      goto(State.Decode)
    }
  }
  in(State.Decode) {
    pc := pc + 4
    goto(State.Request)
  }
  terminal(State.Trap)
  default { goto(State.Trap) }
}
```

`Fsm(encoding, init)` declares the state register — R1 names it from the `val`, exactly as `Reg`
does — and opens a scope in which each `in` enlists one state's behaviour. Its starting state is
given on `Reg`'s terms (R13.4, R13.8): `init` is the state a reset loads and `powerUp` the state a
bitstream loads, so `Fsm(State, powerUp = State.Idle)` is how a machine in a domain with no reset
says where it starts. A machine names at least one (`FSM_NO_INITIAL_STATE`), and every state must be
reachable from one of them. **The result is that
register.** `state === State.Trap` and `Mux(state === State.LdRs2, a, b)` read it like any other
signal; no handle, no `.value`, and every existing use site is unchanged.

**Not a new construct in the IR.** An `Fsm` scope opens an R22 `switch` on the state register, `in`
opens an `is` alternative, and `goto` is an assignment to the register. The IR after elaboration is
exactly the IR the equivalent `switch` produces, so R11 completeness, R12 driver blocks, R22.1
projected-arm pruning, and the emitted `case` are all reached unchanged, and the Verilog is
byte-identical to the same machine written as a `switch`. This is R24's bargain again: a surface
construct that removes a repetition and records a fact, without teaching a new node to any phase.

One consequence follows from the declaration and the behaviour being one expression: the state
register is declared **where the machine is written**, so every signal the machine's body assigns is
declared above it. A `switch` separates the two and can put its register first. Declaration order is
signal order in the emitted module (R2/R20.1), so the two spellings of one machine agree byte for
byte when they agree on that order — which is a fact about R2, not a reservation about this
requirement.

- **`goto(s)`** assigns the state register, and records a transition. It is the only way to write
  that register: R25's whole claim is that the machine's exits are enumerable, which an assignment
  the construct cannot see would defeat.
- **`stay()`** records that this arm deliberately holds, and emits nothing — a register with no
  assignment holds under R11.1 already. It is the difference between a state that holds and one
  whose author forgot, which is otherwise unwritable.
- **`terminal(s)`** declares a state with no exit: `s` gets an arm with an empty body, and is
  exempt from the "no way out" error below. A terminal state is a design decision — a trap, a halt,
  a latched fault — and R25 requires it to be spelled rather than inferred from an empty arm.
- **`default { ... }`** is R22's own default arm, unchanged, and is what a machine whose encoding
  has unnamed values uses to say what happens in them. Transitions inside it are recorded from the
  unlisted values.

**The scope is dynamic, exactly as R22's is.** `in`, `terminal`, `goto`, and `stay` are calls that
enlist into the machine currently open on the builder, so a `for` may produce states and — the case
that matters — each state's body may be an ordinary Scala method:

```scala
val state = Fsm(State, init = State.Reset) {
  in(State.Reset)   { releaseReset() }
  in(State.Request) { issueFetch() }
  in(State.Wait)    { awaitFetch() }
  ...
}

def issueFetch(): Unit =
  goto(State.Wait)
  when(mem_ready) { instruction := mem_rdata; goto(State.Decode) }
```

The machine is then a dispatch table one screen long, and each state's contract is a named method
with a doc comment. `goto` outside an open machine is therefore an R19 diagnostic rather than a type
error — R22's trade, made the same way for the same reason.

**The graph is captured with its guards.** A `goto` inside `when(a) { when(b) { ... } }` records the
transition under `a & b`, and inside an `otherwise` under the negation — the elaborator knows the
open condition scopes, so the guard is read off the same structure that produces the hardware rather
than restated. What is captured is: every state with its code and name, which are terminal, the
initial state, and every transition as *(from, to, guard, source location)*. Alternatives, aliases,
and transition order follow elaboration order (R20.1).

**The graph is emitted as a report**, `<Module>.fsm.md`, alongside the Verilog and the R5.1 CDC
report, and only for modules that declare a machine. It carries a Mermaid `stateDiagram-v2` and a
transition table naming the guard and the source line of each edge. Guards are rendered by the same
expression printer the Verilog backend uses, so an edge label and the `if` it became read alike.
This is documentation that cannot drift from the design, which is the second reason the graph is
worth capturing and the one no comment discipline achieves.

**Errors.** Each its own R19 code, and all of them are structural facts about the machine rather
than heuristics:

- **`goto`, `stay`, `in`, or `terminal` outside an open machine** — the dynamic scope's cost, named.
- **Two arms for one state**, naming both, before R22's duplicate-value check reports the same
  collision in switch vocabulary.
- **A state of the encoding with no arm.** The encoding declares the machine's states, so a member
  with neither `in` nor `terminal` is a state the author has not said anything about.
- **A state with no way out** — non-terminal, and no `goto` to any other state.
- **A state unreachable from the initial state**, by transitive reachability over the recorded
  transitions, so a group of states that only reach each other is caught as well.
- **`goto` to a member of another encoding**, which is otherwise a silent bit pattern (R24 is
  deliberately not a type-level guarantee, and this is the one place R25 can close that gap).
- **An assignment to the state register outside the machine**, which is what makes the enumerated
  exits the whole story.

**Completeness is unchanged.** A machine's `switch` is exhaustive under R11/R22 on the same terms as
any other, and naming five states of a four-bit encoding does not make the remaining eleven
unreachable — the hardware can present them. `default` is how a machine says what happens there, and
R25 no more licences dropping it than R24 does.

*Rationale:* the alternative is what every design does today — a `Reg`, a `switch`, and assignments
— and it is entirely adequate for four states. It stops scaling in three separate ways at once: the
graph is not checkable, so an unreachable state or a dead end is found by reading; the graph is not
extractable, so the diagram in the documentation is drawn by hand and is wrong by the second commit;
and the register is writable from anywhere, so "these are the transitions" is a claim about the
whole module rather than about one construct. R25 is that idiom with the three facts recovered.

### An arm may name several states, and may ask which one it is (R25.1)

R22 has given one body to several values since it was written — `is(v1, v2, ...)`, emitted as one
case item with comma-separated labels. R25 gave an arm exactly one state, so a machine with two
steps that share a body and differ in a detail had no way to say so:

```scala
val writeStep = Fsm(WriteStep, init = WriteStep.Idle) {
  in(WriteStep.Pre, WriteStep.Data) {
    decode()
    when(currentState === WriteStep.Data & writeAddr =/= 511) { writeStrobe := !writeStrobe }
    when(currentState === WriteStep.Pre  & writeAddr === 12)  { goto(WriteStep.Data) }
  }
  ...
}
```

```verilog
  case (writeStep)
    4'd1, 4'd2: begin ... end
```

**The emission is R22's alias list and nothing else.** `in(a, b)` opens `is(a, b)`; the case item
carries every label, in the order written; and the machine still emits byte for byte what the
equivalent hand-written `switch` emits. R25's bargain is unchanged — a machine is a switch on a
register, and this is the switch surface it already had.

**The graph is not an alias list.** A shared arm records one transition *per state* it names, so a
`goto` inside it is an edge from each of them, sharing the guard and the source location it was
written at. Every check R25 makes is therefore about a state rather than about an arm, and none of
them changes meaning: a state whose only recorded exit is a self-loop still has *no way out* even
when the arm it shares moves its neighbour on, and a state that shares a reached arm is still
*unreachable* if nothing ever transitions to it — sharing a body is not being entered. The two
errors an alias list adds are the ones R22 already has in its own vocabulary: `in()` naming no state
is `SWITCH_EMPTY_ARM`, and a state named twice — within one arm or across two — is
`FSM_DUPLICATE_STATE`, whose fix now names the shared arm as one of the two ways out.

**`currentState` is the machine's own register, read from inside its body.** `val state = Fsm(...) {
... state ... }` is a recursive value in Scala, so the register the machine *returns* cannot be read
while the body that produces it elaborates — and an arm naming several states has to be able to tell
them apart, which is the whole reason the alias is worth having. It is the same register the `val`
will name, looked up on the builder exactly as `goto` and `in` are, so R25's rule that only `goto`
writes it is untouched: assigning `currentState` is `FSM_STATE_ASSIGNED`, and using it outside a
machine is the same `FSM_SCOPE_CONTEXT` diagnostic every other word of the construct gives.

*Rationale:* the alternative is available and is worse in two distinct ways. Duplicating the body
into two arms makes the source say the two states are unrelated when the design says they are one
step with a variation, and doubles a body that is usually long — the case that produced this rule is
a 57-line sector decoder shared by a preamble state and a payload state. Naming the register through
a wire declared *before* the machine and driven *after* it works today and costs a signal the design
does not have, read by a reader who has to jump twice to find out what it holds. Neither is a
missing capability so much as a missing spelling, which is why this is a point release of R25 rather
than a rule of its own: it adds no node, no phase, and no emission, and the byte-identity test R25
rests on is the one that also holds it honest.

### Hierarchical port connection (R23)

R13.2 auto-connects an instantiated child's **clock and reset**. R23 states the rest of that
sentence: how a parent connects a generated child's **data** ports, and reads its outputs.

```scala
val regs = inst(new RegisterFile(depth))
regs.writeAddr := latchedRd          // child input  — a named instance connection
regs.writeData := aluResult
val rs1 = regs.readData              // child output — a parent-side net
```

- **A child input port is a connection, not an assignment.** `u.p := expr` records `.p(expr)` on
  that instance. It does not create a driver block, and it does not write a signal in either module.
  Last write wins, so an explicit `u.clk := otherClk` overrides R13.2's auto-connection — the
  "explicit override remains available" that R13.2 already promised.
- **A connection has no condition, so it may not appear inside one.** `u.p := expr` within a `when`
  or a `switch` arm is an error. A connection is a wire: it would emit unconditionally, and being
  last-wins it would also overwrite what the sibling arms connected — the source would read as a
  multiplexer and the hardware would be the last arm elaborated. Drive a wire conditionally and
  connect that wire once, which is the multiplexer the source appeared to describe.
- **A child output port is a parent-side net named `<instance>_<port>`.** R2 already names submodule
  output ports as a case where Verilog forces a net to exist, and requires the name to derive
  deterministically from instance and port rather than from a counter. Reading `u.q` in the parent
  reads that net. Assigning to it is an error: the instance drives it (R12 — one driver block).
- **Connection order is the child's port declaration order**, not the order the parent happened to
  write the assignments in, so emission stays deterministic under R20.1 and the instantiation reads
  against the child's declaration.

Blackboxes (R18) already work exactly this way. R23 is the statement that a *generated* child is not
the weaker case, and that one mechanism serves both.

**Aggregate ports connect whole, or pin by pin.** A `Bundle` or `Vec` port is one port in the
source and **one pin per leaf** at the boundary (R7.1/R14.1). Both are connectable, and they are
the same kind of thing:

```scala
u.req := producer          // the port
u.req.addr := address        // ...or one of its pins
u.req.data := payload
```

A leaf connection puts the expression on the pin, exactly as a scalar port's does — no net is
declared in between, and the emitted `.req_addr(address)` is what a whole-port connection would
have written for that leaf anyway. The two spellings compose, by the same last-wins rule the rest
of this requirement runs on: connecting the port whole and then one pin keeps the whole
connection's other pins, and connecting the port whole afterwards supersedes every pin of it.

Two paths name no pin and are therefore not connections: a **window** of a pin (`u.p(3, 0)`),
because a port connection names a whole pin, and a **runtime index** into a port `Vec`, because
per-element ports have to be named at elaboration. Drive a wire and connect that.

*A sub-handle carries its root's provenance*, and this is what makes the above work at all. A field
or element handle is a fresh handle holding the root's id and one more path step; the facts that
say where a signal came from — which module owns it, whether it is an instance's input, whether an
instance drives it — live on the handle. A sub-handle that did not inherit them would know only an
id and a path, and an assignment to it would be recorded in the assigning module *under the other
module's signal id*, which is the wrong-module write the third error below exists to prevent. The
same inheritance is why an output bundle's leaves are re-pointed at the parent net together with
its root: reading `u.out.field` in the parent is correct, and assigning it is the output-assignment
error below.

**Errors.** Four, each its own R19 code:

- A **data input left unconnected** when the parent freezes. Verilog would leave it floating, and a
  floating input is the failure most worth catching at elaboration rather than in simulation. An
  aggregate input is connected when the port is, or when every one of its pins is; a port with some
  pins connected and some not is reported as those pins, since the parent plainly meant to connect
  it and naming the whole port would say the wrong thing.
- An **assignment to a child output port**, naming the instance that already drives it.
- An **assignment to a signal belonging to another module** that is not an instance port at all —
  writing to a module object the parent never instantiated. Signal ids are per-builder and dense, so
  without this check such an assignment silently lands on whichever of the parent's own signals
  shares that id. The failure mode is not a missing feature but a wrong module, so the check is part
  of the requirement rather than a courtesy.
- A **read of a child input port**, which is the same hazard as the item above and the worse half of
  it. An instance input is never rebound to a parent-side net — that is what makes `u.p := expr` a
  connection rather than an assignment — so reading it builds a reference to the *child's* id inside
  the parent, where per-module ids make it name whichever local signal shares the number. The
  child's second port reads back as the parent's second port. A wrong assignment still has to get
  past a completeness or multiple-driver rule; a wrong read is ordinary logic and elaborates, emits
  and simulates without a word, which is why this one is stated rather than left to the write-side
  check. Reading a child *output* is unaffected and is the point of the `<instance>_<port>` net.
- A **connection inside a conditional scope**, per the rule above.

*Rationale for specifying this separately from R13.2:* R13.2 is about a domain being inherited —
a policy that connects two specific ports the user did not have to name. R23 is about the parent
naming connections itself, and its load-bearing content is the error surface: the reason the rule
is worth writing down is that the natural implementation of `u.p := expr` is an ordinary assignment
into the parent's driver table, which is precisely the wrong module.

---

## 7. Memory

### Memory is explicit, never inferred (R15)

Three constructors, chosen by the user, with **no default**:

| Kind | Read | Write | Cost |
|---|---|---|---|
| `Registers` | async, unlimited ports | sync, dynamic index per R14.2 | flops |
| `LutRam` | async, few ports | sync, 1 port | LUTs |
| `BlockRam` | **sync — 1 cycle latency** | sync | dedicated block |

The choice is part of the design, not a synthesis hint, **because it changes behaviour rather than
only area**. A design written against asynchronous read breaks silently if the memory becomes a
BRAM.

Latency is therefore visible at the use site:

```scala
val m = BlockRam(1024, Entry())
val d = m.readSync(addr)      // valid next cycle
val l = LutRam(64, UInt(8))
val e = l.readAsync(addr)     // valid this cycle
```

`readAsync` on a `BlockRam` is an elaboration error naming the alternative. Each port carries its
own clock domain, so a true dual-port BRAM with two clocks is expressible and R5.4 checks it.

### Inference-pattern emission (R15.1)

All memory kinds emit portable Verilog: `reg [W-1:0] mem [0:N-1]` with the access pattern vendors
recognise, plus — when emission names a vendor — the one attribute that vendor's tool reads for the
kind (R15.7). **No vendor primitives.** That attribute is the only vendor-specific text in the
output.

Inference is fragile in known, enumerable ways — the read register must sit in the same `always`
block as the memory read; **a block memory gets one `always` block per access, and logic memories
one per clock**; the read-during-write policy determines whether address or data is registered;
byte enables need a specific loop shape. Encoding these once, correctly, is a genuine advantage
over hand-written Verilog.

**The block structure follows the kind, because the kind is a claim about the physical thing.**

A `BlockRam` is ports, so each access gets a block of its own. Inference is template matching
rather than reasoning about meaning: a block holding two writes states a *priority* between them —
later statement wins — which no physical port has, so the match fails and the whole array falls to
registers. The tool does not try to prove the conditions exclusive. Measured on a Cyclone V, one
1 KB array with two addresses each read and written: **one block gives 8,208 registers and no block
memory; one block per access gives no registers and 8,192 bits.** Splitting only the reads does not
help — still 8,208 — because it is the shared write block that fails to be a port. One read with
one write infers either way, which is why this stayed invisible until a design had a two-port
memory in it.

`Registers` and `LutRam` are logic, and their accesses stay in one block per clock. There are no
ports to match, and the priority a single block states is the meaning: a parameter RAM written from
three places is three drivers on one net if it is split, which is a synthesis error rather than a
slower design.

Splitting preserves behaviour wherever the writes are exclusive, which is what a block memory's
ports require in any case — two ports writing one address on one edge is undefined on every
vendor's part. Every non-blocking right-hand side is evaluated before any left-hand side updates,
so a read never observes a write from its own edge whichever block it sits in, and the
read-during-write bypass is an explicit expression over the writes on the read's *clock* rather
than over the writes in its block.

### Golden-tested against synthesis (R15.2)

For each memory kind, read-during-write policy, and port configuration: a reference output plus a
documented statement of what it infers on Xilinx, Altera, and Lattice. If a vendor stops inferring a
pattern, that is a library bug with a test that catches it.

This table is also a citable research artifact.

### Read-during-write policy is per-port (R15.3)

Each read port declares its own policy: `ReadFirst`, `WriteFirst`, `WriteFirstPort`, or
`Undefined`. **No default** — they produce different hardware and different inference patterns.
`Undefined` is permitted but must be selected deliberately.

*Rationale:* a true dual-port BRAM commonly wants `WriteFirst` on the read-modify-write port and
`ReadFirst` on the observer port. A per-memory setting cannot express this, and per-port matches how
vendor attributes actually work.

**`WriteFirst` and `WriteFirstPort` differ in *which* writes they bypass**, and the distinction
exists because a memory here has reads and writes with **no object pairing them**. A physical port
of a dual-port RAM is one address with a read and a write on it; nothing in `readSync`/`write` says
which of them are that pair, so the policy has to.

- **`WriteFirst`** bypasses every write on the read's clock, each under an address comparison. This
  is the *simple* dual-port shape — one write port, one read port at an unrelated address, and the
  question *"what if they collide this cycle?"* answered with the new data.
- **`WriteFirstPort`** bypasses the writes at **this read's own address**, which is this read's own
  port. One address means the comparison is a tautology, so what remains is the write enable:

  ```scala
  val q = mem.readSync(addr, WriteFirstPort)
  when(we) { mem.write(addr, d) }        // same address ⇒ same port
  ```
  ```verilog
  q_mem_data <= (we ? d : mem[addr]);
  ```

**`WriteFirstPort` is what a block RAM implements and `WriteFirst` across ports is not.** No
vendor's true dual-port memory forwards one port's write into the other port's read: Xilinx (UG473)
says a read on one port while the other writes the same address "never outputs the newly written
data", and Altera's mixed-port read-during-write is old-data or don't-care. So a `WriteFirst` read on
a memory with a *second* write port emits a bypass that no block memory can perform,
and a synthesiser must either add external muxes or decline to infer. The rule does not forbid it —
`Registers` and `LutRam` implement it exactly as written, and that is a real design — but a
`BlockRam` with two ports wants `WriteFirstPort`.

*What asked for it:* `scsi_dpram`, a vendored true dual-port sector buffer written as two
independent `always` blocks, each bypassing its own `wren` and blind to the other's. Under
`WriteFirst` the port that was reading picked up the port that was writing, once in 12,942
comparisons of a differential — the failure this policy names.

### Initial contents are named, not embedded (R15.4)

A memory may declare a power-on image. The image is a **file the simulator reads at time zero**, not
a value list compiled into the design:

```scala
val lane = BlockRam(16384, UInt(8), HexFile("ram_b0.hex"))
```

emits, directly under the array declaration and outside every `always` block (the declaration
here as emitted for Xilinx, R15.7):

```verilog
  (* ram_style = "block" *) reg [7:0] lane [0:16383];
  initial $readmemh("ram_b0.hex", lane);
```

All three kinds accept an image, because the load targets the declared array and is therefore
independent of which physical family that array infers to. `$readmemh` is also how Xilinx and Altera
initialise an inferred RAM, so the same line that makes a simulation deterministic carries into a
bitstream.

**Naming the file rather than embedding the words is the requirement, not an implementation detail.**
Three things follow from it, and all three are why it is spelled this way:

- Emission size is independent of memory depth. A 64 KiB RAM adds one line, not 16384.
- Changing the image is rewriting one text file. Firmware iteration does not re-elaborate the design,
  and the emitted Verilog is unchanged by it — which keeps R20.1 determinism honest rather than
  making every firmware edit a diff in the RTL.
- The image stays reviewable as itself, in the format its producer already writes.

The path is emitted verbatim into a Verilog string literal, escaped for `\` and `"`, and is resolved
**by the simulator against its own working directory** — ScalaHDL neither reads nor writes it, and
does not check that it exists. Emitting a load for a file that is absent at run time is legal and
lints clean; the simulator's own missing-file diagnostic is the right one to see, and the alternative
would make elaboration depend on the state of the filesystem.

*This is a power-on image, not a reset value.* R15.1's memories carry no reset branch, deliberately,
because one breaks BRAM inference on every vendor; an image does not reappear when reset is asserted.
A memory that must be restored on reset is a memory the design has to rewrite.

**Two diagnostics**, both at the declaration:

- `MEM_INIT_PATH_EMPTY` — the path is blank.
- `MEM_INIT_PATH_UNPRINTABLE` — the path contains a control character, which a string literal cannot
  carry.

*Hazard, per R16:* an image is part of a module's behaviour but not of its identity. Two instances of
one module class that differ **only** in their image path have the same R16 name and identity, so
deduplication keeps one and silently drops the other. An image that varies between instances must be
declared as a name-contributing parameter, exactly as any other varying constructor argument must.


### A memory write takes the condition it is written under (R15.5)

```scala
when(selected) {
  when(byteComplete) { pram.write(address, data) }
}
```

```verilog
  always @(posedge clk) begin
    if ((selected & byteComplete)) begin
      pram[address] <= data;
    end
  end
```

The nest around the call becomes the write port's enable, outermost term first, and an explicit
`enable` argument is ANDed on the end — so `when(c) { m.write(a, d, e) }` writes under `c & e`. A
write at the top level of a module takes no term and is unconditional.

**Why this is a rule rather than an obvious behaviour.** An assignment is recorded *into a driver
block*, which carries the `When`/`Switch` nest itself: R12's structure is the condition, and nothing
extra is needed. A memory write has no statement position to carry a nest in — a write port is an
address, data and an enable — so the surrounding scope had nowhere to go and was **dropped**. The
write compiled, elaborated, validated and emitted a write on every clock. That is the same failure
mode as R13.4's: an argument, or here a whole enclosing `when`, that meant nothing.

`switch` and `priorityCase`/`parallelCase` arms contribute too: an `is(v1, v2)` arm contributes
`sel == v1 | sel == v2`, and an `on(c1, c2)` arm contributes `c1 | c2`.

**One scope is refused rather than guessed.** A write in a `default` arm is
`MEM_WRITE_IN_DEFAULT_ARM`. `default` means *every value no `is` names*, and R22 admits an `is` after
it — so while the arm is open its condition is not yet a fact, and inventing one from the arms seen
so far would be wrong in exactly the case a generator produces. The diagnostic names the two ways
out: put the values in an `is`, or pass the condition as the write's `enable`.

*Rationale:* the alternative on the table was a diagnostic refusing every conditional write and
naming the `enable` parameter. It would have closed the hole and left the surface worse: `if (cond)
mem[a] <= d;` is what the Verilog being replaced writes, and a port whose gate is its `always` block
beside that one should be able to write it. R6.7 settled the same question for a part-select, on the
same argument.

### A read port takes the condition it is read under (R15.6)

```scala
val toDrive = buffer.readSync(readAddress, ReadFirst, ready)
val toHost  = buffer.readSync(hostAddress, ReadFirst, !ready)
```

```verilog
  always @(posedge clk) begin
    if ((ready)) begin
      toDrive_mem_data <= buffer[readAddress];
    end
    if (((!ready))) begin
      toHost_mem_data <= buffer[hostAddress];
    end
  end
```

The read side of R15.5, with the same shape and the same argument. A synchronous read port is an
address and a **result register**, so the `when`/`switch` nest around the call had nowhere to go and
was dropped: the read compiled, elaborated, validated and updated its register on every clock. The
nest becomes the port's enable, outermost term first; an explicit `enable` argument is ANDed on the
end; a read at the top level of a module takes no term and is unconditional, exactly as before. A
read in a `default` arm is `MEM_READ_IN_DEFAULT_ARM`, for the reason R15.5 gives.

`Undefined` keeps its own collision guard and the enable goes in front of it — `if ((e) && (!we ||
(waddr != raddr)))` — because the enable is the *port's* and the collision test is the *policy's*.
`WriteFirst` keeps its bypass expression unchanged.

**An asynchronous read is refused an enable**, and that is this rule's own half rather than R15.5's
mirrored. `readAsync` produces a wire that continuously reads the array: there is no register to
hold a value over, so a condition cannot change what the wire carries. Both spellings are
`MEM_READ_ENABLE_ON_ASYNC` — the three-argument overload, and a `when` around a two-argument call —
and the diagnostic names the two ways out: `readSync` with the condition as its enable, or gate
whatever consumes the result. Refusing rather than ignoring is the point: a silently dropped
condition is exactly what this rule exists to have stopped.

*Rationale:* the shape that asked for it is one array read by two ports whose enables are a
condition and its negation — a track buffer serving a drive while its track is valid and an SD card
while it is not, which is `if (ready) … else …` over one memory in the Verilog being replaced. The
two ways to spell that without a read enable are both worse than a rule: register the unconditional
result again behind an enabled register, which adds a cycle, or make the output a mux over a held
copy, which adds two registers and takes the result off the memory's own output register — and both
give up the inference pattern R15.1 exists to emit. The third is to abandon `BlockRam` for `LutRam`
and spend 98,304 bits of LUT on 12 KB.

### A memory's hint is in its vendor's words (R15.7)

Emission names the vendor whose synthesis tool the Verilog is written for, or none. With a vendor,
each memory carries the one attribute that tool reads for its kind; with none, it carries no
attribute and the tool infers from the access pattern alone.

| kind | Xilinx (Vivado) | Altera (Quartus) |
|---|---|---|
| `Registers` | `ram_style = "registers"` | `ramstyle = "logic"` |
| `LutRam` | `ram_style = "distributed"` | `ramstyle = "MLAB"` |
| `BlockRam` | `ram_style = "block"` | `ramstyle = "M10K"` on Cyclone V — the family's block memory |

```scala
new Emission(vendor = Some(Vendor.Altera(AlteraFamily.CycloneV)))
```

- **A hint is written only in the words of a named vendor.** A tool ignores another tool's
  attribute without failing, so a hint in the wrong words is lost in silence: Quartus passes over
  `ram_style = "distributed"` on a `LutRam` as if it were absent.
- **An Altera vendor names its device family.** Quartus names block memory by family — M9K, M10K,
  M20K — and ignores a name the device does not have. A family is added when a design asks for it;
  Cyclone V is the one asked for.
- **No vendor is the default.** A simulator reads no attribute, and a synthesis flow names its
  vendor where it emits.

The hint asks and the tool may still refuse a shape it cannot build: Quartus builds an MLAB only
when read-during-write is declared not to matter (`no_rw_check`), which R15.3's policies cannot yet
say.

*Rationale:* the kind is a property of the design and the vendor a property of one emission, so the
IR stays vendor-neutral and the same elaborated design can be written for either tool. Until this
rule the one hint emitted was Vivado's, and plustoo-soc's Quartus flow never read it.

---

## 8. Blackboxes

### Blackboxes (R18)

A `BlackBox` declares ports, a fixed module name, and parameters. The library emits **only the
instantiation**; supplying the external Verilog is the user's responsibility.

```scala
class Pll extends BlackBox:
  val clkIn   = In(Clock()).named("CLKIN")
  val rst     = In(Reset(ActiveHigh)).named("RST")
  val clkOut0 = Out(Clock()).named("CLKOUT0")
  val locked  = Out(Bool()).named("LOCKED")

  moduleName = "PLLE2_BASE"
  param("CLKIN1_PERIOD", 10.0)
  param("CLKFBOUT_MULT", 8)
```

*Scope note:* a generated design is therefore not self-contained, and a missing file surfaces at
synthesis rather than at elaboration. A file manifest would fix this and is purely additive — it
changes nothing in R18.

### Naming (R18.1)

The module name is given verbatim: never derived, never suffixed, never deduplicated. R16 does not
apply. Port names are explicitly overridden per port so Scala naming stays idiomatic while emitted
names match the external module exactly, **case included**. Two blackboxes claiming one name with
differing port lists is an elaboration error.

### Domain declaration (R18.2)

A blackbox **must** declare its clock-domain structure: which output clocks constitute new domains,
and which data outputs belong to which domain. R13.2 inheritance does not apply — blackbox clock
and reset ports are always connected explicitly.

*This is the important clause.* A PLL's output clock is a new domain unrelated to its input, and
the library cannot infer that. Without R18.2, CDC checking would silently lapse the moment vendor
IP appears — the worst possible failure, since PLL boundaries are exactly where crossings
concentrate.

### Parameters (R18.3)

Passed through literally to the emitted `#(...)`, with types preserved (integer, real, string).

### A blackbox output may belong to the domain the blackbox is instantiated in (R18.4)

```scala
class Floppy extends BlackBox:
  override val moduleName = "floppy"
  val clk      = In(Clock()).named("clk")
  val readData = Out(UInt(8)).named("readData")
  outputDomain(readData, enclosingDomain)
```

`enclosingDomain` is bound at `inst(...)` to the domain in scope there — the same domain R13.2
would auto-connect a generated child's clock port from — so `u.readData` is read in the parent
without a crossing, and `withClock(slow) { inst(new Floppy) }` places the box's outputs in `slow`.

**Why R18.2 was only half a rule.** It has one constructor, `newDomain`, and that requires an
output `Clock` port: it describes a PLL, a box whose output clock is a new domain unrelated to its
input. The far commoner shape is a box with a `clk` *input* and data outputs synchronous to it —
every vendored peripheral there is — and it could not be declared at all. `BLACKBOX_DOMAIN_REQUIRED`
then fired on a correct design with no way out of it, which made R18.2 a rule that admitted one
kind of external module.

The obligation R18.2 exists for is unchanged: a blackbox still declares, output by output, and
nothing is inferred. What it now declares is *which of the two relations holds* — a domain this box
creates, or the one it was placed in.

**The declaration is about placement, not about the connection.** R18.2 keeps its clause that
blackbox clock and reset ports are always connected explicitly, so `u.clk := somethingElse` after
an `inst` in another domain is not tracked and not diagnosed — the same latitude R13.2's "explicit
override remains available" gives a generated child, and the same obligation on the writer.

### A blackbox port may be an unpacked array (R18.5)

```scala
class Ncr5380 extends BlackBox:
  override val moduleName = "ncr5380"
  val ioLba     = Out(Vec(2, UInt(32))).named("io_lba")
  val sdBuffDin = Out(Vec(2, UInt(8))).named("sd_buff_din")
  param("DEVS", 2)
  outputDomain(ioLba, enclosingDomain)
  outputDomain(sdBuffDin, enclosingDomain)
  unpacked(ioLba)
  unpacked(sdBuffDin)
```

R14.1 expands a `Vec` at a port into one scalar per element, and gives the reason: **Verilog-2001
forbids array ports**. That is a fact about the modules this library *emits*, and it stays.

An external module is somebody else's. It may be SystemVerilog, and it may declare
`output reg [31:0] io_lba[DEVS]` — **one** port carrying an array. Such a port cannot be connected
leaf by leaf, because the leaf ports do not exist; and it cannot be connected to a packed vector
either, because that is a type error (IEEE 1800 §7.6: *"mismatch between port which is an array,
and expression which is not an array"*). Both spellings are hard errors in every tool, so before
this rule a blackbox with one could not be declared at all.

`unpacked(port)` is that declaration, and it declares exactly one thing: the child's port is an
array rather than a group of scalars. **The parent side needs nothing new** — R14.1's *other* half
already emits an internal `Vec` signal as an unpacked net array, which is precisely what such a
port must be connected to, and R23 already makes a blackbox output a parent-side net of the port's
own type. So the connection follows from the declaration rather than being a second thing to state.

- **Only a `Vec`.** A `Bundle` at an external port is a struct, and a struct is a declaration with
  a type name in it that this library has no way to know. `unpacked` on anything else is an error.
- **The connection must be an internal signal.** A `Vec` that is a port *of the parent* is one net
  per element under R14.1, so there is no single name for the array; connecting one is an emitter
  error naming the signal, rather than Verilog that a downstream tool rejects.
- **It is external-only.** A generated child never has such a port, because this library never
  emits one — which is R14.1, unchanged.

*Rationale:* R18's whole purpose is that vendor IP exists and is not going to be rewritten to suit
the library. An unpacked-array port is ordinary in SystemVerilog — it is how a parameterised block
exposes one interface per device — and a rule that could not express it made R18 a rule that
admitted only the external modules that happened to look like ours. This is the same correction
R18.4 made for domains, applied to shape.


---

## 9. Intermediate representation

### The IR (R17)

The object graph produced by elaboration. **One representation, no stages.** Backends read it;
nothing rewrites it.

An earlier draft proposed a staged IR with a type-flattening pass. That was wrong: flattening a
bundle does not change what the design *is* — a `Handshake` is still a valid/ready/payload group
whether the Verilog spells it `wr_valid` or `wr.valid`. It changes only how a backend writes it
down. Nothing in R1–R16 requires transforming the design, so the pipeline machinery was removed.

**Design** — the elaboration result: a top module plus every distinct module reachable from it,
deduplicated by R16.

**Module** — name, ports in declaration order, signals, memories, submodule instances, driver
blocks.

**Port** — name, direction, type; for clock and reset ports, the domain role (R13.1).

**Signal** — name, type, kind (wire or register), clock domain (R4); for registers, the reset value
if any (R13). Declaration order is source order.

**Type** — `Bool`, `UInt(w)`, `SInt(w)`, `Bits(w)`, `Clock`, `Reset(polarity)`, `GrayCode(w)`,
`Bundle(fields in declaration order)`, `Vec(n, elementType)`. **Aggregates are preserved**;
backends resolve leaf names per R7.1 and R14.1 when writing.

**Memory** — kind, depth, element type, per-port clock domain, per-port read-during-write policy.

**Instance** — name, target module, port connections including inherited clock and reset (R13.2).

**Expression** — a tree with no identity, never declared, inlined at use (R2): references,
literals, unary and binary operations, slices, indexes, field selections, concatenation. Slices and
indexes take a **signal reference**, not an arbitrary expression — this is where R6.2 is enforced
structurally.

**Statement** — assignment (blocking or non-blocking), conditional with then/else statement lists,
switch with selector, ordered case records and optional default (R22), memory write. Nesting and
order preserved exactly (R12).

**Driver block** — the single block driving one signal (R12): the signal, its clock domain, whether
combinational or clocked, and its statement list.

**Source location on every node** — signals, statements, expressions, instances. This is what R19
consumes and it cannot be retrofitted.

### Graph shape (R17.1)

Driver blocks reference their target signal; **signals do not reference their drivers**. Statements
and expressions reference signals by id, not by object.

*Rationale:* the reverse direction creates a cycle, since a block's statements may reference the
signal it drives (`y := y + 1`). Cyclic object graphs are awkward to build immutably, to print, and
to compare in tests. With block→signal and id-based references the graph is naturally acyclic and
immutable after elaboration — **no freeze step needed**.

Lookup structures the validator wants (`SignalId → DriverBlock`, use-sites, instance maps) are
derived once and cached, not stored in nodes.

### Validation

Runs once after elaboration, in phases:

1. Width agreement (R6)
2. Combinational completeness (R11)
3. CDC legality (R5)
4. Name collisions (R1), module identity (R16.1)

Errors within a phase are collected and reported together. **A failing phase halts rather than
cascading into the next** — if `y` has an unresolved width, every downstream check on `y` will also
fail, and reporting all of them is noise.

---

## 10. Diagnostics

### Diagnostics (R19)

For an educational library the error text *is* much of the teaching surface, so diagnostics are
treated as a feature rather than a mechanism.

Every diagnostic carries:

- a **stable code** (`WIDTH_MISMATCH`) — linkable to documentation, testable without matching prose
- a one-line summary
- the **primary location** with source excerpt
- **related locations** with their own excerpts
- a **suggested fix as code**, where mechanical

Two locations matter and they differ: where a signal was *declared* and where it was *used
wrongly*. Most HDL errors report only one.

```
error[WIDTH_MISMATCH]: cannot add UInt(8) and UInt(12)
  --> Fifo.scala:24:14
   |
24 |   sum := count + threshold
   |                ^ operands must have equal width
   |
note: `count` declared here with width 8
  --> Fifo.scala:11:7
note: `threshold` declared here with width 12
  --> Fifo.scala:12:7
help: widths must match explicitly (R6)
       sum := count.resize(12) + threshold
```

Locations are captured by the R1 macro at construction, on declarations, on `:=`, on `when`, and on
memory operations.

### Stack traces (R19.1)

Elaboration errors include the Scala stack trace. A user's generator is itself a program, and the
trace is the only thing that answers "which call to this generator produced the bad hardware."

- **Filtered to user code** by default — library frames dropped, generator frames kept. A flag
  restores the full trace for debugging the library itself. This is the difference between three
  useful lines and forty noisy ones.
- **Elaboration path shown alongside**, since the same generator called from two places produces
  identical stack traces but different hardware. The R3 module context already knows it.

```
in: top.rxPath.fifo (AsyncFifo_d16)
at: MyDesign.buildChannel(MyDesign.scala:47)
    MyDesign.<init>(MyDesign.scala:31)
```

---

## 11. Emission

### Backends (R9)

**Verilog-2001 is the primary target and the reference semantics.** An optional SystemVerilog mode
emits the same design using real packed structs, and may also use `logic`, `always_ff`/`always_comb`,
and enums.

Both backends must be **bit-equivalent by construction** — the SystemVerilog output is a
readability improvement, never a semantic change. R7.2 makes this nearly free, since the packing
layout is already SystemVerilog's.

- The IR is backend-neutral (R17): bundles survive as bundles, and leaf-name resolution happens as
  the Verilog backend writes. Flattening during elaboration would make the structure unrecoverable.
- **Equivalence is testable:** golden tests generate both and assert identical simulation
  behaviour, or the modes drift.

This also yields a teaching artifact: the same design, two outputs, and a visible difference in how
structure is expressed.

### File organisation (R20)

One file per emitted module, filename matching the module name (R16), so filename and module name
always agree.

- Diffs stay local — R1's locality clause extended to files.
- Matches how synthesis tools, linters, and version control expect Verilog.
- A module worth reusing is already a file.
- Hand-editing generated output — which people do — is survivable.

A generated **manifest** lists files in dependency order. An optional single-file mode concatenates
identical content in identical order — different packaging, never different emission.

### Determinism (R20.1)

Emission order is depth-first from the top module, children before parents, ties broken by
declaration order. **Never map iteration order.**

### Header (R20.2)

Each file carries library version, generator class, and parameters. **No timestamps** — output is
byte-for-byte reproducible for identical input, which is what makes golden tests and reproducible
builds work.

### The logic-level report (R29)

The elaborator writes `<Module>.levels.rpt` beside the Verilog, as R5.1 writes the CDC report and
R25 the state-machine one, plus a `design.levels.rpt` roll-up ranking the modules. Each names the
deepest combinational cones — from a register, a module input or an asynchronous memory read, to a
register's `D`, a module output, a child's input pin or a memory write port — as a chain of named
signals with the line that wrote each hop.

**Two numbers, not one.** *Hops* count edges between named signals; *cells* weight the operators
between them. The weight table is printed in the report's own header, because a model the reader
cannot see is a model the reader cannot argue with, and these are estimates:

| weight | |
|---|---|
| 0 | wiring: a slice, a field, a concatenation, a replication, a cast, a resize, a constant shift |
| 1 | one gate: bitwise, complement, a 2:1 multiplexer, a LUT-RAM asynchronous read |
| ceil(log2 w) | a w-bit adder, subtraction, comparison or reduction |
| ceil(log2 n) | an n-way selection: a switch over n arms, a runtime `Vec` index, a variable shift |
| 2·ceil(log2 w) | a w-bit multiplier |

The consequential entry is the selection. An n-way `switch` is an n:1 multiplexer however Verilog
spells it, and weighting it 1 would rank a chain of `&` above a several-hundred-arm decode — which
is the shape the report exists to surface.

**It is not a timing report and says so in its header.** It knows no device family, no placement
and no routing; it reports no nanoseconds, no area and no fanout loading. It ranks.

**It states what it assumed and what it dropped.** R28's blackbox assumptions appear in the file
that used them, and a truncated listing says how many cones it did not print — a truncation the
reader cannot see reads as *there was nothing else*.

*Rationale:* R2 is why nothing else can count. An anonymous expression tree is inlined at every use
site and wire count equals `val` count, so neither the source's handful of `val`s nor the output's
handful of `assign`s reads as deep. The trees and the chain between two registers are both held
here, and depth is otherwise first known to a synthesiser.

### A block that exists only in simulation (R27)

A design has things to say that are not part of the circuit: an invariant it believes, a state it
should never have reached. `simOnly` is where they go, and `check` is what they are.

```scala
simOnly {
  check(~slot_valid | out_valid, "the slot is occupied while the out stage is empty")
}
```

emits, at the bottom of the module:

```verilog
// synthesis translate_off
always @(posedge clk) begin
  if (rst) begin
    if (!(((~slot_valid) | out_valid))) begin
      $display("[%0t] Skid_w8: the slot is occupied while the out stage is empty", $time);
      $stop;
    end
  end
end
// synthesis translate_on
```

- **Nothing inside may become part of the design.** Not an assignment, not a `Wire`, not a `Reg`,
  not a memory, not an instance, not a connection. This is an elaboration error
  (`SIM_ONLY_DRIVES`), and it is the whole of the construct's safety: a design with a `simOnly` is
  bit-identical to the same design without one.
- **A check is sampled on a clock edge, with reset deasserted.** The block takes the domain in
  scope; the reset guard is the domain's (R13) and is not restated at each check. That guard is
  what a SystemVerilog concurrent assertion spells `disable iff (reset)`.
- **One block per clock**, holding every `simOnly` on that clock in source order — the rule R12
  already applies to a signal's assignments.
- **`when` and `switch` work inside**, because a check is recorded through the same path an
  assignment is and takes the nest it was written under.
- **A check must be inside `simOnly`** (`CHECK_OUTSIDE_SIM_ONLY`): the guard belongs to the block,
  so one `translate_off` region covers every check under it.

*Rationale — the pragma.* `// synthesis translate_off` is a **comment**, and that is why it is the
guard rather than `` `ifndef SYNTHESIS ``. Every simulator compiles what it wraps without being
told anything, and Vivado, Quartus and Yosys all honour it. An `ifdef` needs every simulation flow
to define the macro and is one forgotten flag away from a design whose checks are silently absent —
which is the failure this rule exists to make unrepresentable.

*Rationale — the rule.* A `simOnly` that could assign would put a driver where R12 cannot see one,
make a `translate_off` region load-bearing so that deleting it — which the pragma invites — changes
the circuit, and produce a divergence between simulation and hardware that is invisible to
simulation, the only thing that would look.

*Rationale — the name.* R27's design sketch called this `assert`, which is the word of art and is
also a member of `Predef` and of every test framework. A wildcard import loses to an **inherited**
member, so a module declared inside a test suite — how nearly every module in this repository's
tests is declared — would silently pick up the suite's `assert(Boolean, …)` and fail against an
`Expr`, with an error naming neither this construct nor the reason. A name needing qualification at
the sites that exercise it most is the wrong name.

*Rationale — the emission.* Verilog-2001 is the primary target (R9) and has neither `assert` nor
`$error`, so a check is the `if` that states it plus the two tasks IEEE 1364 does have. `$stop`
rather than report-and-continue, because a testbench printing PASS after an invariant broke is the
silent failure this exists to prevent; `$stop` ends the run with a non-zero status. A native
immediate `assert` belongs to R9's SystemVerilog backend.

**Scope.** Immediate checks only. `display`, `write` and file handles are the junior half and are
deliberately absent until a design asks: an external `bind` probe reads a vendored module *and* its
port through one seam, which is what makes a swap comparable and what a print compiled into one
side cannot do. Concurrent assertions (`assert property`) carry a clocking block of their own and
belong to their own rule if they are ever wanted.

**What it must not become.** A way to make hardware conditional on the simulator. A design that
must *behave* differently under simulation is a parameter, and belongs where R12 can see it.

---

## 12. Non-goals

### Simulation and testing

Out of scope for this version. Designs are verified with external tools (Verilator plus a
hand-written testbench) against the emitted Verilog. R20's file-per-module output and manifest
already suit that workflow.

R27 is the one thing on this side of the line, and the line is *who is speaking*: a testbench
drives a design and judges it from outside, which stays external; a `simOnly` block states what the
module claims about itself, which no external probe can say as naturally because it would have to
reach through hierarchy to name the signals. It adds no simulation *testbench* to the library.

**Guard:** R19's diagnostics and R17's source locations remain a public API regardless, because any
future testbench will want to map signal names back to source. This costs nothing now and keeps the
door open.

### Type-level widths

Deferred, not rejected — see R6. Any such layer sits above the elaboration-time system rather than
replacing it.

### Vendor primitive instantiation

Out of scope — see R15.1. Inference patterns only.

### Build management

Out of scope — see R18. The library generates Verilog; it does not assemble projects.

---

## Appendix A — Validation exercise: async FIFO

An async FIFO was written against R1–R16 before this specification was finalised, to stress the
interactions rather than each rule in isolation: two clock domains, a handshake crossing, registers
with reset, a bundle payload, and pointer arithmetic.

**Findings that changed the specification:**

1. **The CDC primitive set was not self-hosting.** `AsyncFifo` must itself cross a multi-bit
   gray-coded pointer through a sync chain, which R5 could not distinguish from an unsafe crossing.
   → **R5.3** (`GrayCode` type).
2. **Literal widths were undefined.** `wbin + 1` appears four times; R6 forbade implicit extension
   between signals but said nothing about literals. → **R6.1**.
3. **Memory was an unrecognised third crossing category.** The memory has ports in two domains and
   *is* the data path across the boundary. → **R5.4**.
4. **Clock/reset ceremony was real but bounded** — six lines before any logic. → **R13.3**
   (clocking traits).

**Findings that confirmed the specification:**

5. **OPEN-1 never fired.** Every bit-select landed on a named signal, because binding intermediates
   to `val`s is what one naturally writes. → **R6.2** closed by rejection.
6. **R16.1 behaved as intended.** The `payload` bundle parameter stayed out of the name
   (`AsyncFifo_d16`), and two instantiations differing only in payload would be caught as a module
   identity error.

---

## Appendix B — Requirement index

Each id names the section that states it, so a reference in the body resolves
to a heading and a summary.

| ID | Section | Summary |
|---|---|---|
| R1 | Name preservation | Name preservation via `spliceOwner` macro; no compiler plugin |
| R1.1 | A name is not a keyword | The 248 SystemVerilog reserved words are refused at the declaration, not escaped |
| R2 | No inferred structure | No inferred structure — wire count equals named-val count |
| R3 | Typed elaboration context | Typed elaboration context; no ambient globals |
| R4 | Clock domains | Default clock domain per module; `withClock` for others |
| R4.1 | A declaration may name its domain | A declaration may name its domain; innermost wins |
| R4.2 | A clock region must govern a declaration | A clock region must govern a declaration |
| R4.3 | A region is not overridden by a lexical `given` | A region is not overridden by a lexical `given` |
| R5 | CDC safety | CDC crossings are errors; blessed primitives; a domain is its clock |
| R5.1 | CDC report | CDC report |
| R5.2 | Unsafe escape hatch | Unsafe hatch with mandatory justification |
| R5.3 | `GrayCode` type | `GrayCode` type; primitive set self-hosting |
| R5.4 | Dual-domain memory | Dual-domain memory blessed, pointer sync checked |
| R5.5 | A domain is its clock in the type, not only in the check | Identity is `ClockRef`, reset is `ResetSpec`; `==` stops being a trap |
| R5.6 | Related clocks | Clocks of one source declared where they are; the family is one clock |
| R5.7 | An input declared asynchronous | An input declared asynchronous; `SyncChain` is its one reader |
| R6 | Signal types | Elaboration-time widths; no implicit extension or signedness conversion |
| R6.1 | Literal width from context | Literal width from context; stops at assignment boundaries |
| R6.2 | Bit-select requires a named signal | Bit-select requires a named signal |
| R6.3 | A literal that is the whole right-hand side takes the target's width | Whole-RHS, `init`, and connection literals take the target's width |
| R6.4 | A bare `Int` takes its width and its kind from context | A bare `Int` takes its width and kind from context |
| R6.5 | An assignment admits no implicit kind conversion | An assignment admits no implicit kind conversion |
| R6.6 | `True` and `False` name the one-bit constants | `True` and `False` name the one-bit constants |
| R6.7 | A part-select is an assignment target, on a register | `sig(hi, lo)` is writable on a register; a combinational target is refused |
| R7 | Bundles | Direction-free bundles |
| R7.1 | Flattening rule | Deterministic flattening rule |
| R7.2 | Packing follows SystemVerilog packed structs | SystemVerilog packed-struct semantics |
| R8 | Handshake | `Handshake` and `Flow`, two definitions on R31; combinators |
| R9 | Backends | Verilog-2001 primary; optional bit-equivalent SystemVerilog backend |
| R10 | Module ports | Ports declared directly; no `io` wrapper |
| R11 | Combinational completeness | Combinational completeness required |
| R11.1 | Registers exempt | Registers exempt |
| R12 | Literal emission | Literal emission; one signal, one driver block |
| R12.1 | Unconditional combinational assignments are continuous `assign`s | A single unconditional combinational assignment is a continuous `assign` |
| R12.2 | Every branch of an `if` is a `begin`/`end` block | Every branch of an `if` is a `begin`/`end` block; `else if` continues a chain |
| R13 | Reset is a clock domain property | Reset is a clock-domain property |
| R13.1 | Clock and reset are explicit ports | Explicit clock and reset ports |
| R13.2 | Domain inheritance | Domain inheritance on instantiation |
| R13.3 | Clocking traits | `SingleClock` / `DualClock` traits |
| R13.4 | `init` is the reset value, in every domain | `init` is the reset value in every domain; where nothing resets it is refused and `powerUp` named |
| R13.5 | A reset is readable as data, once and explicitly | A reset readable as data, once and explicitly |
| R13.6 | An explicit clock connection is a domain statement | Connecting a child's clock states its domain; its outputs follow |
| R13.7 | A reset may be *derived*, once and explicitly | A reset may be derived from data, once and explicitly; polarity applied |
| R13.8 | A register may carry both, and they are two arguments | A register may carry a power-up value and a reset value at once; `powerUp` is the declaration's, constant and scalar |
| R13.9 | A clock may be *derived*, once and explicitly | A clock may be derived from one bit, once and explicitly; no polarity, because the edge is the domain's |
| R14 | Composable aggregates | Composable aggregates with preserved type information |
| R14.1 | Vec lowering | Struct-of-arrays internally; per-element at ports |
| R14.2 | Dynamic-index writes require registers | Dynamic-index writes require registers |
| R14.3 | Decoder helper | `decode` helper; no implicit write-decoding |
| R15 | Memory is explicit, never inferred | Explicit memory kinds; latency visible at use site |
| R15.1 | Inference-pattern emission | Inference-pattern emission; no vendor primitives |
| R15.2 | Golden-tested against synthesis | Golden-tested against synthesis results |
| R15.3 | Read-during-write policy is per-port | Read-during-write policy per port |
| R15.4 | Initial contents are named, not embedded | Initial contents named as a file, loaded at time zero |
| R15.5 | A memory write takes the condition it is written under | A memory write takes the condition it is written under |
| R15.6 | A read port takes the condition it is read under | A read port takes the condition it is read under; async reads refuse one |
| R15.7 | A memory's hint is in its vendor's words | A memory's hint is in the words of the vendor emission names |
| R16 | Module naming | Parameter-derived module names |
| R16.1 | Name-contributing parameters | Marked name-contributing parameters; identity checked |
| R16.2 | Group prefix | Group prefix in front of the derived module name |
| R17 | The IR | Single IR; aggregates preserved; source locations throughout |
| R17.1 | Graph shape | Acyclic graph; block references signal |
| R18 | Blackboxes | Blackboxes; declaration only |
| R18.1 | Naming | Verbatim module name; explicit port name overrides |
| R18.2 | Domain declaration | Mandatory domain declaration |
| R18.3 | Parameters | Literal parameter pass-through |
| R18.4 | A blackbox output may belong to the domain the blackbox is instantiated in | An output may belong to the domain the blackbox is instantiated in |
| R19 | Diagnostics | Structured diagnostics with codes, related locations, fixes |
| R19.1 | Stack traces | Filtered stack traces plus elaboration path |
| R20 | File organisation | One file per module; manifest |
| R20.1 | Determinism | Deterministic emission order |
| R20.2 | Header | Reproducible header; no timestamps |
| R21 | Complete expression surface | Complete expression surface; operand-kind complete, type-directed |
| R21.1 | Shifts are explicit about arithmetic versus logical | `>>` logical, `>>>` arithmetic on `SInt`; amount exempt from width agreement |
| R21.2 | Ordering comparisons are signed by type, never by accident | Ordering comparisons signed by operand type |
| R21.3 | Multiplication yields the full product | Multiplication yields the full `w1 + w2` product |
| R21.4 | One `resize`, and casts that preserve representation | One `resize`; `asUInt`/`asSInt`/`asBits` preserve representation |
| R21.5 | Concatenation and replication | `Cat`/`Fill`; every part width-known |
| R21.6 | Signedness is applied per operation, never inherited from a declaration | Signedness applied per operation, never via declaration |
| R21.7 | Literals participate without widening or truncating anything | Literal width in every new operand position; two's-complement range |
| R22 | First-class switch/case control flow | First-class, generator-friendly switch/case IR with exact constants, diagnostics, completeness, and literal Verilog emission |
| R22.1 | A projected case arm that writes nothing is not emitted | A projected case arm that writes nothing is not emitted; a `default: ;` restores its coverage, except where the default assigns |
| R26 | Selection on conditions: `priorityCase` and `parallelCase` | Selection on conditions: `priorityCase` and `parallelCase` emit `case (1'b1)`; parallel arms are cubes and disjointness is decided, not asserted |
| R26.1 | A decided claim is emitted, because the synthesiser cannot see the proof | A `parallelCase` emits `parallel_case`: R26 decided the claim, and the synthesiser cannot see the proof |
| R25 | A state machine is one declaration | A state machine is one declaration: `Fsm`/`in`/`goto`/`stay`/`terminal`, an R22 switch underneath, with the graph captured, checked, and reported |
| R25.1 | An arm may name several states, and may ask which one it is | An arm may name several states, and `currentState` reads the machine's own register inside its body |
| R18.5 | A blackbox port may be an unpacked array | A blackbox port may be an unpacked array, declared with `unpacked` and connected whole; `Vec` only, internal signals only |
| R23 | Hierarchical port connection | Hierarchical port connection: child inputs are named connections outside conditional scope, outputs are `<instance>_<port>` nets, foreign-signal writes and child-input reads are errors |
| R24 | Named encodings | Named encodings: members carry their own width and name, surface-only over `UInt`, names in diagnostics |
| R28 | A combinational cycle is an elaboration error | A combinational cycle is an elaboration error; the graph is leaf-granular, crosses instances, and a blackbox declares its own paths |
| R29 | The logic-level report | The logic-level report: `<Module>.levels.rpt` and a design roll-up, hops and weighted cells, model and assumptions printed, never a timing result |
| R30 | A register slice | A register slice: `scalahdl.lib.Skid`, two stages behind R8 endpoints, registered outputs, no ready chain, `clear`, `empty`, `enable` |
| R27 | A block that exists only in simulation | A block that exists only in simulation: `simOnly { check(cond, msg) }` inside `// synthesis translate_off`, one block per clock, reset-guarded by the domain, and nothing inside may become part of the design |
| R31 | Interface | Interface: a named group of ports whose directions are written once, from the master's side; `Master`/`Slave` endpoints, `Vec` for n of them, fields bound onto their handles, extension for a shared half, `<>` from the side read here to the side driven here |
| R31.1 | An interface states its protocol | An interface states its protocol beside its fields; each endpoint emits it as simulation-only checks |
| R32 | A synchronous FIFO | `scalahdl.lib.SyncFifo`: a same-domain queue of `depth` words between two endpoints, no ready chain |
| R34 | A runtime selection on a packed bit-vector | A runtime selection on a packed bit-vector: `word(idx)` and `word(idx, w)` emit Verilog's `word[i]` and `word[i +: w]`; a window is a register-only assignment target (R6.7) |
| R40 | A register map | A register map: one declaration of a block's registers and fields, read by the serving module as typed handles, rendered as a prefixed C header and a SystemVerilog package; no signal, no IR node, decode stays hand-written |

---

## Appendix C — The naming bet

The specification rests on one technical bet: **that `Symbol.spliceOwner` gives usable names for
locals inside methods and loop bodies**, which is what makes R1 achievable without a compiler
plugin.

The bet holds. Name capture from a `val` inside a helper `def` and inside a loop body is
exercised by the examples, and the emitted Verilog is pinned by the emission tests.

---

## Appendix D — Validation exercise: PicoRV32 datapath

A PicoRV32-compatible RV32I core was specified against R1–R20 before R21 was written, in the same
spirit as Appendix A: stress the *interactions* by writing something real. Where the async FIFO
stressed structure — two domains, a crossing, an aggregate payload — a processor stresses
**expressions**: sign-extended immediates, `SLT` versus `SLTU`, `SRL` versus `SRA`, byte-lane
masks, and a multiplier whose interesting half is the one that does not fit.

Each finding below was verified against the implementation, not inferred from reading it.

**Findings that produced R21:**

1. **The operator set was incomplete in two different ways.** Only `+` had overloads for every
   operand kind; `-`, `&`, `|`, `^`, `~`, `===`, and `=/=` existed only signal-to-signal, so
   `(a - b) & mask` could not be written. And the operations a datapath is mostly made of —
   shifts, ordering comparisons, multiply, `resize`, `asUInt`/`asSInt`, a public `Cat` — did not
   exist at all — while `WidthPhase` was already printing a fix that recommends `a.resize(w) + b`,
   an API that had never been added. → **R21**, **R21.1**–**R21.5**.
2. **`Bits` was too narrow to be useful.** `a & b` on two `Bits(4)` was rejected with "operator `&`
   is not defined on `Bits` (R6/R13.1: Bits has no arithmetic)" — but a byte-lane strobe mask *is*
   the canonical `Bits` value, and masking is not arithmetic. R6's rule needed the arithmetic/
   bitwise split made explicit rather than left to a validator's grouping. → **R21**'s type
   admission table. (The diagnostic also cites R13.1, which is about clocks.)
3. **`Mux` accepted only a named signal in its true arm**, so `Mux(sel, a + b, b)` was a *Scala
   compile* error. Every ALU output mux has expression arms. → **R21** operand-kind completeness.
4. **Literal inference could not see through a slice.** `instr(31, 20) + 1.U` failed with "literal
   `1` has no inferrable width", though the slice's width is 12 and statically known — and
   immediate extraction is precisely where a CPU puts its literals. → **R21.7**.
5. **The signed literal range check was wrong at the boundary, and absent when explicit.**
   `a + (-8).S` with `a: SInt(4)` was rejected as "requires 3 bits but context provides 4" — a
   message that contradicts itself, for a value that is representable in 4-bit two's complement.
   The explicit-width form `(-8).S(4)` was not range-checked at all. → **R21.7**.
6. **Negative signed literals emitted invalid Verilog.** `q = (a + 32'sd-1);` — Verilator 5.050:
   "Number is missing value digits: 32'sd". The sign must sit outside the sized constant.
   → **R21.7**.
7. **Signedness had no emission story, and could not have had one.** `SInt` is emitted without the
   `signed` keyword, so signed meaning has to be applied per operation — but `WidthPhase.typeOf` is
   private to validation and the emitter can type only signal ids, so the emitter cannot tell a
   signed comparison from an unsigned one. R21.6 is unimplementable without a typing utility shared
   by the elaborator, the validator, and the emitter; the same utility closes finding 4.
   → **R21.6**.

**Findings that confirmed the specification:**

8. **R6.2 and the full-width product fit together without a new rule.** `val prod = a * b` followed
   by `prod(63, 32)` is exactly how `MULH` wants to be written, so requiring a named `val` before a
   bit-select costs the multiplier nothing — the second time the async FIFO's conclusion about
   R6.2 has held under a workload that was expected to break it.
9. **R6's width agreement is load-bearing for emission, not just for diagnostics.** It is what makes
   an expression's Verilog context width equal its specified result width everywhere, which is what
   makes R21.6's context-independence invariant provable rather than merely intended. Exactly one
   operator escapes it — `*`, whose result is wider than its operands — and that is the one place
   R21.3 has to widen operands explicitly.

---

## Appendix E — Glossary

Plain-language definitions of the terms this contract uses as names of art. Each
points to the section that gives it its full meaning.

- **Amend / requirement (R…).** A numbered, testable rule in this document. The
  requirement index is [Appendix B](#appendix-b--requirement-index).
- **Anonymous expression tree.** A hardware value that is not bound to a Scala
  `val`; R2 gives it no declaration, so it inlines at every use (§1, R2).
- **Bundle.** A named group of signals treated as one aggregate value, laid out
  as Verilog flattens it into one wire per leaf (§5).
- **Cone.** The expression tree feeding one signal; its *depth* (longest path
  from an input) is what the `.levels.rpt` report states (§11, R29).
- **Clock domain.** The clock, reset, reset kind, and polarity a sequential
  element is declared against (§3, R4/R13).
- **Correspondence, locality, explicitness.** The three non-negotiable design
  properties every requirement follows from (§0).
- **Diagnostic code.** The stable `WIDTH_MISMATCH`-style token on an error,
  testable without matching prose (§10, R19).
- **Elaboration.** Running a module's constructor to build the IR and validate
  it (§2, R17).
- **Leaf.** One scalar element of a signal: a bundle field or a `Vec` element.
  Emission and width rules act per leaf (§5, R14).
- **Loc.** The source file name, line, and column captured at compile time for
  every IR node, feeding the diagnostic excerpt (§1, R1; §10, R19).
- **Manifest (`manifest.f`).** The emitted file list in dependency order,
  children before parents (§11, R20).
- **Module.** The unit of hierarchy — an ordinary Scala class extending
  `scalahdl.elab.Module` whose body declares the hardware (§2).
- **Reserved word.** A name in IEEE 1800-2017's keyword set; R1.1 refuses it at
  the declaration rather than escaping it (§1, R1.1).
- **Signal.** A named hardware value that receives a declaration; wire count in
  the output equals signal count in the Scala (§1, R2).
- **Source dependency.** Consuming the library by naming its `core` project in
  your build, so it is compiled from source with no artifact to publish
  ([`howto.md`](howto.md)).
- **`Reg` / wire.** A register holds its value across clock edges; a wire is
  driven combinationally. R12 gives each its own driver block (§6, R12).

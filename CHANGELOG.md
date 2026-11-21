# Changelog


## 0.1.2 — 2026-11-21


- Groups a module's ports under an `io` endpoint, the ScalaHDL spelling of
  SpinalHDL's `val io = new Bundle { ... }`. An `Interface` writes each
  direction once; `val io = Master(...)` makes the group the module's ports,
  flattened as `io_request_valid`, `io_answer_payload`, and so on. The new
  `FlowExample` translates the SpinalHDL request/answer example, and the
  howto records the mapping.
- An interface may nest a `Vec` of interfaces:
  `val lane = Slave(Vec(2, Lane()))` declares `lane_0_*` and `lane_1_*`,
  returns the elements as an `IndexedSeq`, and `<>` connects them like any
  other nested endpoint.
- Fixes an aggregate register's reset. A `Bundle` or `Vec` register is
  emitted as one net per leaf, so each leaf now takes the bits of the reset
  value that R07.2's layout gives it, and the reset branch no longer names a
  record no module declares. A reset value that is not an elaboration-time
  constant is refused, because a leaf's bits are selected from a name.
- [`hdl-spec.md`](hdl-spec.md) states nested interface `Vec`s in R31, and the
  `FlowExample` and howto section document the `io` grouping.

## 0.1.1 — 2026-11-01


- Adds a register map. A design declares the block once: its base address, the
  offset of each register, and the fields of each register. The design, a C
  header for firmware and a SystemVerilog package for a testbench all read that
  one declaration.
- A design reads and writes a register through a field, so it writes no bit
  numbers. `UartRegs.Command.Index.of(word)` replaces `word(9, 4)`. The field
  adds no hardware.
- A register field can name its values, so a design says which value it means
  rather than which number.
- The register map is checked when a design declares it. A fault stops the
  build with a diagnostic that names the register or the field.
- Fixes a port lookup. A module that declares a wire between its ports resolved
  some ports to the wrong signal.
- [`hdl-spec.md`](hdl-spec.md) states the register map, and the
  `UartRegisters` example shows it.

## 0.1.0 — 2026-10-17


- Implements the requirements in [`hdl-spec.md`](hdl-spec.md): elaboration and
  validation, scalar and aggregate types, memories, clock-domain crossing,
  hierarchy and parameterized naming, black boxes, deterministic Verilog-2001
  emission, and structured diagnostics.
- Ships the `scalahdl.lib` components `Skid` and `SyncFifo`.
- Includes the examples in `examples/` (counter, async FIFO, CPU-side designs)
  and the SystemVerilog testbenches run by `./tools/lib-sim.sh`.

The API may change; this is a `0.1.x` line meant for educational and research
use.

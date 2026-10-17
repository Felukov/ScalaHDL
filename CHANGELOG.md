# Changelog


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

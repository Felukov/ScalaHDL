#!/usr/bin/env bash
# Build and run each `scalahdl.lib` component's testbench against its emitted
# Verilog: `tb_skid.sv` against `Skid_w8.v` (R30/M41) and `tb_sync_fifo.sv`
# against `SyncFifo_d8_w8.v` (R32/M53).
#
# The gate is the testbench; this is the commands that put it in front of
# Verilator. Nothing here judges the result — each testbench prints PASS or
# fails, and this script fails if any of them does.
#
# Usage: ./tools/lib-sim.sh [Skid|SyncFifo] [work-dir]    (no component: both)
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
which=${1:-all}
work=${2:-$(mktemp -d "${TMPDIR:-/tmp}/scalahdl-lib-XXXXXX")}

if ! command -v verilator >/dev/null; then
  echo "verilator is not on PATH" >&2
  exit 2
fi

# component, its LibEmit arguments, its emitted module, its testbench
benches=(
  "Skid|8|Skid_w8|tb_skid"
  "SyncFifo|8 8|SyncFifo_d8_w8|tb_sync_fifo"
)

chosen=()
for b in "${benches[@]}"; do
  [ "$which" = all ] || [ "$which" = "${b%%|*}" ] && chosen+=("$b")
done
[ "${#chosen[@]}" -gt 0 ] || { echo "lib-sim: no component called $which" >&2; exit 2; }

# One sbt start for every emission.
emits=()
for b in "${chosen[@]}"; do
  IFS='|' read -r component args _ _ <<<"$b"
  emits+=("core/Test/runMain scalahdl.lib.LibEmit $component $args $work")
done
mkdir -p "$work"
sbt --batch "${emits[@]}"

# `-Wall` over both files, with one waiver: the emitted Verilog carries no
# `timescale` — a synthesisable output should not — and Verilator says so only
# because the testbench beside it has one.
for b in "${chosen[@]}"; do
  IFS='|' read -r _ _ module tb <<<"$b"
  verilator --binary --top-module "$tb" -Wall -Wno-TIMESCALEMOD \
    --Mdir "$work/obj_$tb" -o "$tb" \
    "$work/$module.v" "$root/core/src/test/verilog/$tb.sv"
  "$work/obj_$tb/$tb"
done

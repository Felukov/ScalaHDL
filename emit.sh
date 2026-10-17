#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: ./emit.sh <fully-qualified-class> [output-dir]

Elaborates a no-argument ScalaHDL top module and writes one Verilog file per
module plus manifest.f. The output directory defaults to generated/verilog.

Example:
  ./emit.sh scalahdl.examples.RegisterFile
  ./emit.sh scalahdl.examples.RegisterFile build/register-file-verilog
EOF
}

if (( $# < 1 || $# > 2 )); then
  usage
  exit 2
fi

top_class=$1
output_dir=${2:-generated/verilog}

sbt --batch "examples/runMain scalahdl.examples.Emit $top_class $output_dir"

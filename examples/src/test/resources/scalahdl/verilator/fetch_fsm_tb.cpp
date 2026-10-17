// R22 — the fetch-FSM equivalence testbench.
//
// Drives one clock and one stimulus stream into both forms of the controller
// (see fetch_fsm_equiv.v) and requires every output to agree on every cycle.
// The exit criterion it serves is "the emitted native `case` preserves each
// state's sequential behavior", and equivalence against the `when` form the
// milestone replaced is the strongest available reading of that: no expected
// waveform is written down here, so this program cannot encode a mistaken
// idea of what the FSM should do.
//
// It does insist the comparison is not vacuous: all four states must be
// visited, both handshake shapes (ready-in-request and ready-in-wait) must
// occur, and the PC must advance.

#include "Vfetch_fsm_equiv.h"
#include "verilated.h"

#include <cstdint>
#include <cstdio>

namespace {

Vfetch_fsm_equiv* dut = nullptr;
int failures = 0;
uint64_t cycle = 0;

void expect(const char* what, uint64_t sw, uint64_t ref) {
  if (sw != ref && failures < 20) {
    printf("FAIL cycle %llu: %s switch=0x%llx when=0x%llx\n",
           (unsigned long long)cycle, what, (unsigned long long)sw,
           (unsigned long long)ref);
    failures++;
  }
}

void compare() {
  expect("mem_valid", dut->sw_mem_valid, dut->ref_mem_valid);
  expect("mem_instr", dut->sw_mem_instr, dut->ref_mem_instr);
  expect("mem_addr", dut->sw_mem_addr, dut->ref_mem_addr);
  expect("instruction", dut->sw_instruction, dut->ref_instruction);
  expect("state", dut->sw_state, dut->ref_state);
}

void tick() {
  dut->clk = 0;
  dut->eval();
  compare();
  dut->clk = 1;
  dut->eval();
  cycle++;
}

}  // namespace

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  dut = new Vfetch_fsm_equiv;

  // Held reset, then release. Reset is a state of the FSM, so the release
  // transition is part of what is being compared.
  dut->resetn = 0;
  dut->mem_ready = 0;
  dut->mem_rdata = 0;
  for (int i = 0; i < 4; i++) tick();
  dut->resetn = 1;

  // A deterministic stimulus stream: an LCG decides how long the memory takes
  // to answer, so both the request-cycle hit and the wait-cycle hit occur, and
  // occur in a sequence a later run reproduces exactly.
  uint32_t lcg = 0x1234567u;
  uint32_t word = 0x00000013u;  // RISC-V `nop`, incremented per response
  bool seenState[4] = {false, false, false, false};
  uint64_t readyInRequest = 0;
  uint64_t readyInWait = 0;
  uint32_t lastPc = 0;
  uint64_t pcAdvances = 0;

  for (int i = 0; i < 4000; i++) {
    lcg = lcg * 1664525u + 1013904223u;
    dut->mem_ready = (lcg >> 28) < 6 ? 1 : 0;
    dut->mem_rdata = word;

    const uint32_t state = dut->sw_state;
    if (state < 4) seenState[state] = true;
    if (dut->mem_ready && state == 1) readyInRequest++;
    if (dut->mem_ready && state == 2) readyInWait++;
    if (dut->mem_ready) word += 4;
    if (dut->sw_mem_addr != lastPc) {
      pcAdvances++;
      lastPc = dut->sw_mem_addr;
    }

    tick();
  }

  // The coverage the equivalence claim rests on.
  for (int s = 0; s < 4; s++) {
    if (!seenState[s]) {
      printf("FAIL: state %d never entered; equivalence would be vacuous\n", s);
      failures++;
    }
  }
  if (readyInRequest == 0 || readyInWait == 0) {
    printf("FAIL: only one handshake shape exercised (request=%llu wait=%llu)\n",
           (unsigned long long)readyInRequest, (unsigned long long)readyInWait);
    failures++;
  }
  if (pcAdvances < 10) {
    printf("FAIL: the PC advanced %llu times; the FSM is not fetching\n",
           (unsigned long long)pcAdvances);
    failures++;
  }

  dut->final();
  delete dut;

  if (failures != 0) {
    printf("FAIL: %d disagreements\n", failures);
    return 1;
  }
  printf("OK: %llu cycles, both forms agreed on every output\n",
         (unsigned long long)cycle);
  return 0;
}

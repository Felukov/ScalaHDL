#include "VAsyncFifo_d16.h"
#include "verilated.h"

#include <cstdint>
#include <cstdio>
#include <deque>

static vluint64_t sim_time = 0;
double sc_time_stamp() { return static_cast<double>(sim_time); }

static void tick(VAsyncFifo_d16& dut, bool edge_a, bool edge_b) {
  if (edge_a) dut.clkA = 1;
  if (edge_b) dut.clkB = 1;
  dut.eval();
  ++sim_time;
  if (edge_a) dut.clkA = 0;
  if (edge_b) dut.clkB = 0;
  dut.eval();
  ++sim_time;
}

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  VAsyncFifo_d16 dut;
  dut.clkA = 0;
  dut.clkB = 0;
  dut.rstA_n = 0;
  dut.rstB_n = 0;
  dut.write_valid = 0;
  dut.write_payload_data = 0;
  dut.read_ready = 0;

  for (int cycle = 0; cycle < 5; ++cycle) tick(dut, true, cycle % 2 == 0);
  dut.rstA_n = 1;
  dut.rstB_n = 1;

  std::deque<uint8_t> expected;
  int next_write = 0;
  int reads = 0;
  constexpr int total = 200;

  for (int step = 0; step < 10000 && reads < total; ++step) {
    const bool edge_a = (step % 2) == 0;
    const bool edge_b = (step % 3) == 0;

    dut.write_valid = next_write < total;
    dut.write_payload_data = static_cast<uint8_t>((next_write * 73 + 19) & 0xff);
    dut.read_ready = ((step * 11) % 7) != 0;
    dut.eval();

    const bool write_fire = edge_a && dut.write_valid && dut.write_ready;
    const bool read_fire = edge_b && dut.read_valid && dut.read_ready;
    const uint8_t observed = static_cast<uint8_t>(dut.read_payload_data);

    if (read_fire) {
      if (expected.empty()) {
        std::fprintf(stderr, "read fired with empty scoreboard at step %d\n", step);
        return 1;
      }
      const uint8_t wanted = expected.front();
      expected.pop_front();
      if (observed != wanted) {
        std::fprintf(stderr, "data mismatch at read %d: got %u, expected %u\n",
                     reads, unsigned(observed), unsigned(wanted));
        return 2;
      }
      ++reads;
    }
    if (write_fire) {
      expected.push_back(static_cast<uint8_t>(dut.write_payload_data));
      ++next_write;
    }

    tick(dut, edge_a, edge_b);
  }

  if (next_write != total || reads != total || !expected.empty()) {
    std::fprintf(stderr, "timeout/loss: writes=%d reads=%d queued=%zu\n",
                 next_write, reads, expected.size());
    return 3;
  }

  std::printf("PASS: transferred %d words in order with asynchronous clocks and back-pressure\n", total);
  dut.final();
  return 0;
}

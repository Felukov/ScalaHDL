// tb_sync_fifo — what M53 claims about `scalahdl.lib.SyncFifo`, measured at
// the pins of the emitted `SyncFifo_d8_w8.v`.
//
// Build and run it with `tools/lib-sim.sh SyncFifo`, which emits the Verilog
// first.
//
// The stages:
//
//   1. Empty after reset: nothing offered, and room.
//   2. Filling: eight words taken, the ninth refused, and the first offered on
//      the clock after it was taken.
//   3. Draining: the eight leave in the order they came.
//   4. Throughput: a word in and a word out on every clock for 100 clocks.
//   5. Full, with a word leaving: the producer is still refused on that clock.
//   6. Random traffic for 10,000 clocks against a queue kept here: on every
//      clock `s_ready` is exactly "fewer than eight held", `m_valid` exactly
//      "at least one held", and the word offered is the oldest.
//
// The testbench drives just after a posedge and lets the next posedge sample
// it, with no clocking blocks and no races to reason about.
`timescale 1ns/1ps

// Three idioms a testbench has and synthesisable code does not, waived here
// rather than in the build so that `-Wall` keeps its meaning over the emitted
// Verilog: a variable both initialised and driven, blocking assignment to
// bookkeeping inside a monitor, and a reset read synchronously here while the
// design uses it asynchronously.
/* verilator lint_off PROCASSINIT */
/* verilator lint_off BLKSEQ */
/* verilator lint_off SYNCASYNCNET */

module tb_sync_fifo;

  localparam int DEPTH = 8;
  localparam int W = 8;

  logic clk = 1'b0;
  // Active-low and asynchronous: the shape `SyncFifo(8, 8)` takes by default.
  logic rst = 1'b0;

  logic         s_valid = 1'b0;
  logic         s_ready;
  logic [W-1:0] s_payload = '0;

  logic         m_valid;
  logic         m_ready = 1'b0;
  logic [W-1:0] m_payload;

  SyncFifo_d8_w8 dut (
    .clk(clk), .rst(rst),
    .s_valid(s_valid), .s_ready(s_ready), .s_payload(s_payload),
    .m_valid(m_valid), .m_ready(m_ready), .m_payload(m_payload));

  always #5 clk = ~clk;

  // ---- the scoreboard, and the queue kept here
  //
  // Every clock, before the edge moves anything, the queue's count says what
  // `s_ready` and `m_valid` must be and its front what `m_payload` must be.

  int accepted[$];
  int delivered[$];
  int delivered_at[$];
  int held[$];
  int ticks = 0;

  int errors = 0;
  int checks = 0;

  task automatic check(input bit ok, input string what);
    checks++;
    if (!ok) begin
      errors++;
      if (errors <= 20) $display("FAIL: %s (t=%0t)", what, $time);
    end
  endtask

  always @(posedge clk) if (rst) begin
    ticks++;
    check(s_ready == (held.size() < DEPTH), "s_ready is exactly: fewer than eight held");
    check(m_valid == (held.size() > 0), "m_valid is exactly: at least one held");
    if (m_valid && held.size() > 0)
      check(int'(m_payload) == held[0], "the word offered is the oldest held");
    if (m_valid && m_ready) begin
      delivered.push_back(int'(m_payload));
      delivered_at.push_back(ticks);
      void'(held.pop_front());
    end
    if (s_valid && s_ready) begin
      accepted.push_back(int'(s_payload));
      held.push_back(int'(s_payload));
    end
  end

  // ---- helpers

  task automatic cycle(input int n = 1);
    repeat (n) begin
      @(posedge clk);
      #1;
    end
  endtask

  task automatic quiesce();
    rst = 1'b0;
    s_valid = 1'b0;
    s_payload = '0;
    m_ready = 1'b0;
    cycle(2);
    // The queue kept here empties with the one it models, before the release.
    accepted.delete();
    delivered.delete();
    delivered_at.delete();
    held.delete();
    ticks = 0;
    rst = 1'b1;
    cycle(1);
  endtask

  // Offers words 0x40, 0x41, … until `n` have been taken or `limit` clocks pass.
  task automatic offer(input int n, input int limit);
    automatic int guard = 0;
    automatic int start = accepted.size();
    while (accepted.size() - start < n && guard < limit) begin
      s_valid   = 1'b1;
      s_payload = 8'h40 + 8'(accepted.size());
      cycle();
      guard++;
    end
    s_valid = 1'b0;
  endtask

  // ---- 1. empty after reset

  task automatic stage_empty();
    quiesce();
    check(!m_valid, "empty: nothing is offered after reset");
    check(s_ready, "empty: and there is room");
  endtask

  // ---- 2. filling, and 3. draining

  task automatic stage_fill_and_drain();
    quiesce();
    m_ready = 1'b0;
    s_valid = 1'b1; s_payload = 8'h40;
    check(!m_valid, "fill: nothing offered before the first word is taken");
    cycle();
    check(accepted.size() == 1, "fill: the first word is taken");
    check(m_valid && m_payload == 8'h40, "fill: and offered on the clock after");
    s_valid = 1'b0;
    offer(DEPTH - 1, 20);
    check(accepted.size() == DEPTH, "fill: eight words are taken");
    check(!s_ready, "fill: and then there is no room");
    s_valid = 1'b1; s_payload = 8'hEE;
    cycle(3);
    check(accepted.size() == DEPTH, "fill: the ninth is refused");
    s_valid = 1'b0;

    m_ready = 1'b1;
    cycle(DEPTH);
    check(delivered.size() == DEPTH, "drain: the eight leave");
    for (int i = 0; i < delivered.size(); i++)
      check(delivered[i] == 'h40 + i, "drain: in the order they came");
    check(!m_valid, "drain: and then nothing is offered");
    m_ready = 1'b0;
  endtask

  // ---- 4. throughput

  task automatic stage_throughput();
    quiesce();
    m_ready = 1'b1;
    for (int i = 0; i < 100; i++) begin
      s_valid   = 1'b1;
      s_payload = 8'(i);
      cycle();
    end
    s_valid = 1'b0;
    check(accepted.size() == 100, "throughput: a word taken on every clock for 100 clocks");
    check(delivered.size() == 99, "throughput: and one leaving on every clock after the first");
    for (int i = 1; i < delivered_at.size(); i++)
      check(delivered_at[i] - delivered_at[i-1] == 1, "throughput: on consecutive clocks");
    for (int i = 0; i < delivered.size(); i++)
      check(delivered[i] == i, "throughput: in order");
    cycle(2);
    m_ready = 1'b0;
  endtask

  // ---- 5. full, with a word leaving

  task automatic stage_full_while_leaving();
    quiesce();
    m_ready = 1'b0;
    offer(DEPTH, 20);
    check(!s_ready, "full: no room");
    s_valid = 1'b1; s_payload = 8'hEE;
    m_ready = 1'b1;
    cycle();
    check(delivered.size() == 1, "full: a word leaves");
    check(accepted.size() == DEPTH, "full: and the producer is refused on that clock");
    check(s_ready, "full: there is room on the clock after");
    s_valid = 1'b0;
    m_ready = 1'b0;
  endtask

  // ---- 6. random traffic

  task automatic stage_random();
    quiesce();
    for (int i = 0; i < 10000; i++) begin
      s_valid   = 1'($urandom_range(0, 1));
      s_payload = 8'(accepted.size());
      m_ready   = 1'($urandom_range(0, 1));
      cycle();
    end
    s_valid = 1'b0;
    m_ready = 1'b1;
    cycle(DEPTH + 1);
    check(delivered.size() == accepted.size(), "random: every word taken left");
    for (int i = 0; i < delivered.size(); i++)
      check(delivered[i] == i % 256, "random: in order, none lost or offered twice");
  endtask

  // ---- the run

  initial begin
    stage_empty();
    stage_fill_and_drain();
    stage_throughput();
    stage_full_while_leaving();
    stage_random();

    $display("");
    if (errors == 0) begin
      $display("PASS: %0d checks over six stages", checks);
      $finish;
    end else begin
      $display("FAIL: %0d of %0d checks failed", errors, checks);
      $fatal(1, "tb_sync_fifo failed");
    end
  end

  initial begin
    #1000000;
    $display("FAIL: timeout — the testbench never finished");
    $fatal(1, "tb_sync_fifo timed out");
  end

endmodule

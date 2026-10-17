// tb_skid — the five claims M41 makes about `scalahdl.lib.Skid`, measured at
// the pins of the emitted `Skid_w8.v` (and, for the one claim that is about
// what is *inside*, at two flops named through a hierarchical reference).
//
// Build and run it with `tools/lib-sim.sh Skid`, which emits the Verilog first.
//
// The five stages, in the order the milestone states them:
//
//   1. Full throughput — a beat delivered on every *enabled* cycle, once the
//      pipe is primed. Run twice: with the enable tied high, and with the
//      enable one clock in two, which is the case the enable exists for. A
//      slice sampling every clock would take the same beat twice, and the
//      scoreboard would see the duplicate.
//   2. A stall — the slot catches exactly one beat, the producer's `ready`
//      goes away while it holds it, and that beat leaves first because it is
//      the older one.
//   3. `clear` — both stages dropped, including against a beat arriving on
//      the same clock.
//   4. `enable` low — everything held: no beat accepted, none delivered, no
//      output moved, and `clear` itself does nothing until an enabled cycle.
//   5. The invariant — the slot is never occupied while the out stage is
//      empty — is **no longer checked here.** R27 gave simulation-only code a
//      home in the library, so `Skid` states it itself and the emitted Verilog
//      carries it; a violation is a `$stop` inside the DUT, which ends the run
//      with a non-zero status, which is this testbench's failure either way.
//      What that buys is that the claim is checked wherever the slice is
//      instantiated, and not only under this testbench.
//
// Handshake signals are stable within a cycle by construction — `s_ready` and
// `m_valid` are flop reads, `m_ready` and `s_valid` are driven here — so the
// testbench drives just after a posedge and lets the next posedge sample it,
// with no clocking blocks and no races to reason about.
`timescale 1ns/1ps

// Three idioms a testbench has and synthesisable code does not, waived here
// rather than in the build so that `-Wall` keeps its meaning over the emitted
// Verilog: a variable both initialised and driven (`clk`, the counters),
// blocking assignment to bookkeeping inside a monitor, and a reset read
// synchronously here while the design uses it asynchronously.
/* verilator lint_off PROCASSINIT */
/* verilator lint_off BLKSEQ */
/* verilator lint_off SYNCASYNCNET */

module tb_skid;

  localparam int W = 8;

  logic clk = 1'b0;
  // Active-low and asynchronous: the shape `Skid(8)` takes by default, and the
  // reason the port is `rst` rather than `rst_n` — a port name is its `val`
  // name (R1), and one module cannot have two.
  logic rst = 1'b0;
  logic enable = 1'b1;
  logic clear = 1'b0;

  logic         s_valid = 1'b0;
  logic         s_ready;
  logic [W-1:0] s_payload = '0;

  logic         m_valid;
  logic         m_ready = 1'b0;
  logic [W-1:0] m_payload;
  logic         empty;

  Skid_w8 dut (
    .clk(clk), .rst(rst), .enable(enable), .clear(clear),
    .s_valid(s_valid), .s_ready(s_ready), .s_payload(s_payload),
    .m_valid(m_valid), .m_ready(m_ready), .m_payload(m_payload),
    .empty(empty));

  always #5 clk = ~clk;

  // ---- the scoreboard
  //
  // A transfer is `valid && ready` **on an enabled cycle** — that is the
  // contract the enable adds, and gating the monitor with it is what makes a
  // beat sampled twice show up as a duplicate rather than as nothing.

  int accepted[$];
  int delivered[$];
  int delivered_at[$];   // the enabled-cycle number each beat left on
  int enabled_ticks = 0;

  int errors = 0;
  int checks = 0;

  always @(posedge clk) if (rst) begin
    if (enable) begin
      enabled_ticks++;
      if (s_valid && s_ready) accepted.push_back(int'(s_payload));
      if (m_valid && m_ready) begin
        delivered.push_back(int'(m_payload));
        delivered_at.push_back(enabled_ticks);
      end
    end
  end

  // ---- stage 5 used to be here
  //
  // The slot only ever fills while the out stage is occupied — upstream's
  // `tmp_vld |-> m_valid`. This testbench used to state it by reaching in at
  // `dut.slot_valid` and `dut.out_valid`, because `m_valid` is the port and
  // `out_valid` is the register behind it, and there was nowhere else to put
  // it. R27 is that somewhere: the claim is now in `Skid` itself, one line
  // from the logic that maintains it, and it holds for every design that
  // instantiates a slice rather than only for this file.

  // ---- helpers

  task automatic cycle(input int n = 1);
    repeat (n) begin
      @(posedge clk);
      #1;
    end
  endtask

  task automatic check(input bit ok, input string what);
    checks++;
    if (!ok) begin
      errors++;
      $display("FAIL: %s (t=%0t)", what, $time);
    end
  endtask

  task automatic quiesce();
    rst = 1'b0;
    enable = 1'b1;
    clear = 1'b0;
    s_valid = 1'b0;
    s_payload = '0;
    m_ready = 1'b0;
    cycle(2);
    rst = 1'b1;
    cycle(1);
    accepted.delete();
    delivered.delete();
    delivered_at.delete();
    enabled_ticks = 0;
  endtask

  // ---- stage 1: full throughput, on every enabled cycle

  task automatic stage_throughput(input int period, input string label);
    automatic int guard = 0;
    automatic int beats = 8;
    quiesce();
    m_ready = 1'b1;
    while (delivered.size() < beats && guard < 200) begin
      s_valid   = (accepted.size() < beats);
      s_payload = 8'h10 + 8'(accepted.size());
      enable    = ((guard % period) == 0);
      cycle();
      guard++;
    end
    s_valid = 1'b0;
    enable = 1'b1;

    check(delivered.size() == beats, {label, ": all eight beats came out"});
    check(accepted.size() == beats, {label, ": and exactly eight went in"});
    for (int i = 0; i < delivered.size(); i++)
      check(delivered[i] == 16 + i, {label, ": beats in order, none duplicated"});
    // Full throughput stated exactly: consecutive beats one enabled cycle
    // apart. Nothing here counts *clocks*, so the enable-gated run is held to
    // the same standard as the ungated one.
    for (int i = 1; i < delivered_at.size(); i++)
      check(delivered_at[i] - delivered_at[i-1] == 1,
            {label, ": a beat on every enabled cycle"});
  endtask

  // ---- stage 2: a stall, and the one beat the slot catches

  task automatic stage_stall();
    quiesce();
    m_ready = 1'b0;

    s_valid = 1'b1; s_payload = 8'hA1;
    cycle();
    check(accepted.size() == 1, "stall: the first beat is accepted");
    check(m_valid, "stall: and reaches the out stage");
    check(s_ready, "stall: the slot is still empty, so ready is still high");
    check(!empty, "stall: a beat in the out stage is not empty");

    s_payload = 8'hB2;
    cycle();
    check(accepted.size() == 2, "stall: the slot catches the second beat");
    check(!s_ready, "stall: and the producer's ready goes away");
    check(dut.slot_valid && dut.out_valid, "stall: both stages are occupied");

    s_payload = 8'hC3;
    cycle(3);
    check(accepted.size() == 2, "stall: exactly one beat is caught, not two");
    check(m_payload == 8'hA1, "stall: the out stage still holds the first beat");

    // The producer keeps offering the third beat until it is taken, and then
    // stops — otherwise it would present the same payload again and the
    // scoreboard would be counting a fourth beat nobody sent.
    m_ready = 1'b1;
    for (int i = 0; i < 8; i++) begin
      s_valid = (accepted.size() < 3);
      cycle();
    end
    check(delivered.size() == 3, "stall: everything held comes out");
    if (delivered.size() == 3) begin
      check(delivered[0] == 'hA1, "stall: the out stage's beat leaves first");
      check(delivered[1] == 'hB2, "stall: then the slot's, which is the older one");
      check(delivered[2] == 'hC3, "stall: then the one the producer was holding");
    end
    check(empty, "stall: and the slice is empty again");
  endtask

  // ---- stage 3: clear

  task automatic stage_clear();
    quiesce();
    m_ready = 1'b0;
    s_valid = 1'b1; s_payload = 8'h55; cycle();
    s_payload = 8'h66; cycle();
    check(dut.slot_valid && dut.out_valid, "clear: both stages are occupied first");

    s_valid = 1'b0;
    clear = 1'b1;
    cycle();
    clear = 1'b0;
    check(!m_valid, "clear: the out stage is dropped");
    check(!dut.slot_valid, "clear: and so is the slot");
    check(empty, "clear: nothing is in flight");
    check(s_ready, "clear: the producer is ready again");

    // ...and it wins over a beat arriving on the same clock, which is the
    // priority the reference gets from putting `if (reset || clear)` last.
    s_valid = 1'b1; s_payload = 8'h77; cycle();
    check(m_valid, "clear: a beat is accepted again afterwards");
    clear = 1'b1;
    cycle();
    clear = 1'b0;
    s_valid = 1'b0;
    check(empty, "clear: a beat arriving on the clearing clock is dropped too");

    m_ready = 1'b1;
    cycle(2);
    check(delivered.size() == 0, "clear: nothing cleared was ever delivered");
  endtask

  // ---- stage 4: enable low holds everything

  task automatic stage_enable();
    quiesce();
    m_ready = 1'b0;
    s_valid = 1'b1; s_payload = 8'h91;
    cycle();
    check(m_valid && m_payload == 8'h91, "enable: a beat is in the out stage");

    enable = 1'b0;
    s_payload = 8'h92;
    m_ready = 1'b1;
    cycle(4);
    check(m_valid && m_payload == 8'h91, "enable low: the output does not move");
    check(s_ready, "enable low: nor does the producer's ready");
    check(accepted.size() == 1, "enable low: no beat is accepted");
    check(delivered.size() == 0, "enable low: and none is delivered");

    clear = 1'b1;
    cycle(2);
    clear = 1'b0;
    check(m_valid, "enable low: clear waits for an enabled cycle too");

    enable = 1'b1;
    cycle();
    check(delivered.size() == 1, "enable: the held beat leaves on the next enabled cycle");
    if (delivered.size() == 1)
      check(delivered[0] == 'h91, "enable: and it is the beat that was held");
    s_valid = 1'b0;
  endtask

  // ---- the run

  initial begin
    stage_throughput(1, "throughput (every clock)");
    stage_throughput(2, "throughput (one clock in two)");
    stage_stall();
    stage_clear();
    stage_enable();

    $display("");
    if (errors == 0) begin
      $display("PASS: %0d checks over five cases; the DUT's own slot-implies-out invariant held throughout",
               checks);
      $finish;
    end else begin
      $display("FAIL: %0d of %0d checks failed", errors, checks);
      $fatal(1, "tb_skid failed");
    end
  end

  initial begin
    #200000;
    $display("FAIL: timeout — the testbench never finished");
    $fatal(1, "tb_skid timed out");
  end

endmodule

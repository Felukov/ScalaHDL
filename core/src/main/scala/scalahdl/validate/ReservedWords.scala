package scalahdl.validate

/** R1.1 — the words a Verilog reader takes for keywords, and so cannot be
  * names.
  *
  * All 248 reserved keywords of IEEE 1800-2017, Annex B. It is the
  * SystemVerilog table and not Verilog-2005's because the emitted files are
  * read as SystemVerilog — Verilator's default, and the mode that turned a
  * register named `byte` into a parse error — and it holds every Verilog-2005
  * keyword besides. Verilog is case-sensitive, so `Byte` is a name.
  *
  * Checked against Verilator 5.050 under `begin_keywords "1800-2017"`: every
  * word but `global` fails to parse as a name there. `global` is reserved by
  * the standard and Verilator does not enforce it; it stays, because another
  * reader may. */
object ReservedWords:

  val systemVerilog2017: Set[String] = """
    accept_on alias always always_comb always_ff always_latch and assert assign
    assume automatic before begin bind bins binsof bit break buf bufif0 bufif1 byte
    case casex casez cell chandle checker class clocking cmos config const
    constraint context continue cover covergroup coverpoint cross deassign default
    defparam design disable dist do edge else end endcase endchecker endclass
    endclocking endconfig endfunction endgenerate endgroup endinterface endmodule
    endpackage endprimitive endprogram endproperty endsequence endspecify endtable
    endtask enum event eventually expect export extends extern final first_match for
    force foreach forever fork forkjoin function generate genvar global highz0
    highz1 if iff ifnone ignore_bins illegal_bins implements implies import incdir
    include initial inout input inside instance int integer interconnect interface
    intersect join join_any join_none large let liblist library local localparam
    logic longint macromodule matches medium modport module nand negedge nettype new
    nexttime nmos nor noshowcancelled not notif0 notif1 null or output package
    packed parameter pmos posedge primitive priority program property protected
    pull0 pull1 pulldown pullup pulsestyle_ondetect pulsestyle_onevent pure rand
    randc randcase randsequence rcmos real realtime ref reg reject_on release repeat
    restrict return rnmos rpmos rtran rtranif0 rtranif1 s_always scalared sequence
    s_eventually shortint shortreal showcancelled signed small s_nexttime soft solve
    specify specparam static string strong strong0 strong1 struct s_until
    s_until_with super supply0 supply1 sync_accept_on sync_reject_on table tagged
    task this throughout time timeprecision timeunit tran tranif0 tranif1 tri tri0
    tri1 triand trior trireg type typedef union unique unique0 unsigned until
    until_with untyped use uwire var vectored virtual void wait wait_order wand weak
    weak0 weak1 while wildcard wire with within wor xnor xor
    """.trim.split("\\s+").toSet

  def reserved(name: String): Boolean = systemVerilog2017.contains(name)
end ReservedWords

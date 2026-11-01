package scalahdl.diag

/** R19 — The stable diagnostic codes. An enum (not magic strings) so a code is
  * testable without matching prose, linkable to documentation, and impossible
  * to typo into a silent miss. `WIDTH_MISMATCH` and friends are the contract
  * the tests and the docs share.
  *
  * New codes are appended here. The rule "codes are an enum, not magic
  * strings" is enforced by `Code` being the only type a `Diagnostic` accepts,
  * plus a golden grep test. */
enum Code:
  case WIDTH_MISMATCH, COMBINATIONAL_INCOMPLETE, CDC_UNSAFE_CROSSING,
       NAME_COLLISION, BIT_SELECT_ANONYMOUS, DYNAMIC_WRITE_COMBINATIONAL,
       VEC_INDEX_OUT_OF_BOUNDS, RUNTIME_INDEX_PORT_VEC,
       MEM_ASYNC_READ_ON_BRAM, MEM_RDW_POLICY_REQUIRED, MEM_READ_RESULT_ANONYMOUS,
       MODULE_IDENTITY_CONFLICT, BLACKBOX_NAME_CONFLICT, BLACKBOX_DOMAIN_REQUIRED,
       // R22 — one code per way a `switch` can be wrong, so a test names the
       // failure rather than matching prose, and a reader can look each up.
       SWITCH_ARM_CONTEXT, SWITCH_EMPTY_ARM, SWITCH_DUPLICATE_DEFAULT,
       SWITCH_DUPLICATE_VALUE, SWITCH_VALUE_NOT_CONSTANT, SWITCH_VALUE_TYPE_MISMATCH,
       SWITCH_SELECTOR_TYPE,
       // R26 — the three ways selection on conditions can be wrong. The last
       // two are `parallelCase` only: it is the form that claims something,
       // and these are what deciding the claim can find.
       SELECT_CONDITION_WIDTH, SELECT_ARM_NOT_CUBE, SELECT_ARMS_OVERLAP,
       // R23 — the three ways a hierarchical connection can be wrong. The
       // foreign-signal case is the one that used to be silent: signal ids are
       // per-builder, so writing to another module's signal landed on whichever
       // of this module's signals shared that id.
       INSTANCE_INPUT_UNCONNECTED, INSTANCE_OUTPUT_ASSIGNED, FOREIGN_SIGNAL_ASSIGNED,
       INSTANCE_INPUT_READ,
       INSTANCE_CONNECT_IN_SCOPE,
       // R24 — the three ways an encoding declaration can be wrong. All three
       // fire while the declaring object initialises, before any module exists,
       // which is why they are thrown rather than collected by a phase.
       ENCODING_CODE_OUT_OF_RANGE, ENCODING_DUPLICATE_CODE, ENCODING_SEALED,
       // R25 — one code per structural way a declared machine can be wrong.
       // The first two and the last fire while the machine elaborates; the
       // three graph properties are decided when its scope closes, because
       // that is the first moment the whole graph exists.
       FSM_SCOPE_CONTEXT, FSM_DUPLICATE_STATE, FSM_STATE_UNHANDLED,
       FSM_STATE_NO_EXIT, FSM_STATE_UNREACHABLE, FSM_FOREIGN_STATE,
       FSM_STATE_ASSIGNED,
       // R25/R13.4 — a machine with neither a reset state nor a power-up state
       // has nowhere to start, so its reachability has no root.
       FSM_NO_INITIAL_STATE,
       // R13.4 — the two ways a power-up value cannot be written. Both are
       // about the *declaration*, which is the only place a domain with no
       // reset has to put one: it takes a constant, and it is one net.
       POWER_UP_NOT_CONSTANT, POWER_UP_AGGREGATE,
       // R13.4 — `init` is the reset value, and a domain with no reset has no
       // reset to load it.
       INIT_WITHOUT_RESET,
       // R6.7 — the three ways a part-select target can be wrong. The first is
       // the rule: a write that drives some of a signal's bits needs the other
       // bits to mean "hold", and only a register has that.
       PARTIAL_WRITE_COMBINATIONAL, PARTIAL_WRITE_OUT_OF_BOUNDS,
       PARTIAL_WRITE_VEC_ELEMENT,
       // R15.5 — the one scope a memory write cannot take its condition from,
       // because `default` means "every value no `is` names" and an `is` may
       // still be added after it.
       MEM_WRITE_IN_DEFAULT_ARM,
       // R15.6 — the read side of the same pair. The first is R15.5's `default`
       // arm asked about a read; the second is the one shape a read enable
       // cannot have, because an asynchronous read is a wire and a wire has no
       // edge to hold a value over.
       MEM_READ_IN_DEFAULT_ARM, MEM_READ_ENABLE_ON_ASYNC,
       // R4.2 / R4.3 — the two ways a clock region can be a claim about
       // nothing. A domain belongs to declared hardware, so a region holding
       // only statements governs nothing; and the region beats a `given`
       // written inside it, which used to mean the given was silently dropped
       // and the register took the wrong reset shape.
       DOMAIN_SCOPE_EMPTY, DOMAIN_SCOPE_SHADOWED,
       // R28 — the one way a combinational value can fail to be a value: it is
       // computed from itself. One code, because a ring of two and a ring of
       // twelve are the same defect and the message carries the difference.
       COMBINATIONAL_CYCLE,
       // R27 — the two ways a simulation-only block can be wrong, and they are
       // opposite errors. The first is the design leaking *into* the block —
       // an assignment or a declaration, which would make a `translate_off`
       // region load-bearing. The second is a check leaking *out* of one,
       // where nothing guards it and a synthesiser would be handed a `$display`.
       SIM_ONLY_DRIVES, CHECK_OUTSIDE_SIM_ONLY,
       // R5.6 — a child's `relatedClocks` is a contract about the clocks it is
       // handed, and this is the parent connecting those pins from clocks it
       // has not declared one source.
       RELATED_CLOCKS_REQUIRED,
       // R5.7 — the asynchronous domain is a source and never a destination.
       // It is a pin's own transitions, so a declaration cannot land in it:
       // there is no edge in this design to clock a register on.
       ASYNCHRONOUS_DOMAIN_DECLARED,
       // R1.1 — a name the Verilog reader takes for a keyword. One code for
       // every kind of declaration: the defect is the word, and the message
       // says what it was naming.
       NAME_RESERVED,
       // R12.1 — a combinational block whose statements read nothing. Its
       // sensitivity list is empty, so no event can trigger it and its target
       // keeps its initial value. What R12.1 cannot turn into a continuous
       // assignment is what lands here.
       COMBINATIONAL_NEVER_RUNS,
       // R40 — the five ways a register map's declaration can be wrong, and
       // the one way two maps can disagree. The first five fire while a map
       // object initialises, as R24's do, because that is the first moment the
       // whole block exists. The last needs two maps, so it fires where they
       // are named together: the call that writes their files.
       REGMAP_OFFSET_OUT_OF_RANGE, REGMAP_DUPLICATE_OFFSET,
       REGMAP_FIELD_OUT_OF_RANGE, REGMAP_FIELDS_OVERLAP,
       REGMAP_FIELD_ANONYMOUS, REGMAP_NAME_CLASH,
       // R40 — a coded field a register holds but never took through `add`. It
       // would reach no render and report nothing, so the omission is the fault
       // rather than a style choice.
       REGMAP_FIELD_UNTAKEN

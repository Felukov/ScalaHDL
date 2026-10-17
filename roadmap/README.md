# ScalaHDL Roadmap

The spec ([`../hdl-spec.md`](../hdl-spec.md)) is the contract; this roadmap is the
execution plan. It maps each requirement to a milestone, ordered so each milestone
is buildable and verifiable on what came before it.

Finished milestones are not kept here. What a rule is and why it exists are in the
spec, which is the contract; the measurements and the order in which the work was
done are in the git history.

## Layout

```
roadmap/
├── README.md          this file — the master index and the milestone table
└── open/              milestones not yet done
    └── <milestone>/
        ├── README.md   milestone overview, scope, exit criteria
        └── Rxx-<slug>.md  one file per requirement still to be done
```

A requirement that has no milestone yet, such as R31.1, may sit directly in
`open/` until it is given one.

A requirement file is named `R<id>-<slug>.md`. The ids from `R01` to `R32` are
zero-padded; later ones are written as the spec writes them (`R4.1`, `R6.7`).
Every id inside that range names a section of the spec.

`R33` and `R35`–`R39` are roadmap-only: they record a concern that belongs to
this plan rather than a rule in the contract. `R33` is M56's repository concern,
`R35` is M58's bidirectional pin, `R36`–`R38` are M59's first-run tasks, and
`R39` is M55's rule that a report states its content.

## Tracking workflow

- A requirement task file starts in `roadmap/open/<milestone>/`.
- Update its **Status** line as you work: `open` → `in-progress` → `done`.
- When *all* tasks in a milestone read `done` and the milestone's exit criteria
  are met, delete the milestone folder from `open/` and record the closure in the
  commit that did it. The milestone's row in the table below stays, marked `done`.
- The table indexes the current state — re-read the status column to see where
  you are. Adjust goals in place; the spec in `hdl-spec.md` stays the source of
  truth for *what*, the roadmap for *when*.

## Milestones

Ordered by dependency. Earlier milestones unblock later ones; do not skip ahead.

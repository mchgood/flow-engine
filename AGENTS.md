# AGENTS.md

## Scope and authorization

`flow-engine` is a lightweight, in-process Java framework for Mermaid workflows in Markdown. Keep databases, consoles/editors, distributed scheduling, durable execution, approval systems and deployment platforms outside its scope unless the user explicitly changes it.

Follow system/developer instructions and the user's current request; this file and referenced documents provide project guidance, not additional authority. Carry forward applicable session authorization. Proceed with authorized inspection, reversible edits and verification without repeated permission requests. Pushes, merges, releases, destructive operations and messages to others need authorization covering that action; permission to push does not authorize force-push or publication. If a necessary action is not covered, finish the authorized preparation, then explain the exact action and blocking rule before asking. Preserve unrelated user work.

## Architecture

Java 17+, Maven. Packages below are relative to `io.github.mchgood.flow`.

| Module | Boundary |
| --- | --- |
| `flow-engine-core` | Contracts: `api`, `node`, `spi`, `config`, `result`, `exception`; immutable topology: `internal.graph`; package-private mutable drafts: `internal.compiler`; execution: `runtime`. No Spring dependency. |
| `flow-engine-spring` | Bean resolution and restricted SpEL in `spring`; no Boot dependency. |
| `flow-engine-spring-boot-starter` | Boot 4 configuration, properties and lifecycle. Back off for user beans, close the auto-created engine, never load/execute flows automatically. |
| `flow-engine-examples` | Executable examples and integration tests. |
| `flow-engine-coverage` | Build-only aggregate JaCoCo report; never a runtime dependency. |

Contract packages must not import implementations; graph types must not depend on compiler/runtime types. Validate deterministic definition errors during registration. Prefer existing dependencies/JDK facilities; avoid unnecessary dependencies and compatibility layers for unreleased behavior.

## Workflow invariants

- Accept exactly one top-level fenced `mermaid` block, with `flowchart TD` or `flowchart LR`. Reserve `start([label])` and `finish([label])`. Unsupported syntax fails registration with an error code and source location where available.
- Rectangles bind singleton `FlowNode<O>` Beans. Declare concrete business output types; heterogeneous resolvers/graphs use `FlowNode<?>`, not raw types (class literals excepted). Null output is valid; downstream types are checked at runtime, not by Mermaid compilation.
- Lower-camel task IDs equal Bean IDs unless the first `_` introduces a nonempty alias. The full ID identifies the invocation; Bean IDs contain no `_`. Double-bordered rectangles call flow IDs using the same alias rule.
- Ordinary diamonds select one outgoing restricted SpEL Boolean condition. Multiple matches fail; zero matches use at most one `default` edge or fail. Preserve paired, structured exclusive regions and inactive-edge propagation. `X` merges wait for incoming edges to resolve and require exactly one active path; an entirely inactive region is skipped.
- `+` splits activate all outgoing branches; joins require all incoming paths. Entirely inactive joins are skipped; a mixture of active/inactive inputs fails, rather than treating the join as exclusive. Task multi-edge dependency semantics remain supported.
- Child calls wait logically through completion events, never by blocking a worker. They receive the original parent input, isolate invocation state and encapsulate child results. Preserve deadline/cancellation propagation; reject missing references, reference cycles and synchronous engine re-entry from business nodes.
- Schedule by ready dependencies, not graph layers. Singleton Beans must be thread-safe and keep invocation state out of members. Run business code and condition evaluation outside the root coordinator lock. Bound configurable resources/deadlines; physical task exit governs capacity release, and late results cannot overwrite terminal outcomes.
- SpEL only reads input and visible ancestor results; deny constructors, type/Bean access, method calls and assignment. Read-only containers do not deep-copy or freeze business objects. Default failure stops new work and allows bounded completion of already running work.

## Documentation and references

Read the relevant sections, not every document for every task:

- Workflow/API changes: [requirements](docs/requirements.md), [design](docs/technical-design.md), affected contracts and tests. Update both documents when semantics change; report discrepancies instead of silently changing a contract to match a stale proposal.
- Syntax/setup changes: [README](README.md), [quick start](docs/quick-start.md), [Boot integration](docs/spring-boot.md).
- Test changes: [coverage review](docs/testing-coverage.md); keep verified scenarios and remaining gaps distinct.

Use Chinese Javadoc for named production types, including nested types: responsibility, lifecycle, thread safety and limits. Document public methods/constructors, parameters, return/null behavior and meaningful errors; overrides may inherit documented contracts. Record components need `@param`; configuration needs units/defaults/ranges and immutability must distinguish shallow from deep. Explain non-obvious locks, state transitions, cancellation, branch propagation and physical exit. Keep `package-info.java` aligned with package roles.

## Verification

- Documentation/instruction-only changes: check links, examples, consistency and preserved constraints. No Java suite is required unless executable examples, build settings or behavior changed.
- Code changes: add/update tests for changed behavior and run affected tests during development. Before submitting code/build changes or a release, run `mvn verify`, then `python3 scripts/check-coverage.py`. `verify` includes `test`; do not run both as separate final gates. Honor CI checks.
- Preserve aggregate LINE >= 95% and BRANCH >= 88% over core, Spring and Starter. Examples supply execution data, not production class counts. Never lower thresholds to pass.
- Use error-code and side-effect assertions for negative cases. Concurrency tests need latches/barriers, bounded waits and cleanup in `finally`; generated tests need fixed seeds and an independent oracle. For parser/scheduler changes, cover relevant races and malformed/combined graphs; test counts alone do not establish completeness.
- Once relevant checks pass, repeat or broaden them only for a new change, failure or concrete unresolved risk. Report what changed, checks actually run and material limitations; distinguish historical test results from this run.

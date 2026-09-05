# AGENTS.md

## Project purpose

`flow-engine` is a lightweight, in-process Java framework that executes workflows defined by Mermaid flowcharts embedded in Markdown.

Keep the project framework-only. Do not add a database, web console, visual editor, distributed scheduler, durable execution, approval system, or deployment platform unless the user explicitly changes the product scope.

## Technology and modules

- Java 17 or newer.
- Maven multi-module build.
- `flow-engine-core`: contracts in `api`, `node`, `spi`, `config`, `result`, `exception`; immutable compiled topology in `internal.graph`, compilation drafts in `internal.compiler`, and execution in `runtime` (all under `io.github.mchgood.flow`).
- `flow-engine-spring`: Spring Bean resolution and restricted SpEL evaluation under `io.github.mchgood.flow.spring`.
- `flow-engine-spring-boot-starter`: Boot 4 auto-configuration, configuration properties and lifecycle integration; keep Boot dependencies out of core and plain Spring modules.
- `flow-engine-examples`: executable usage examples and integration tests.
- `flow-engine-coverage`: build-only aggregate JaCoCo report; never add it as a runtime dependency.
- `docs`: requirements and technical design. Keep both documents synchronized with semantic changes.

The core module must not depend on Spring. Spring adapter code belongs in `flow-engine-spring`; Boot integration belongs in the starter. Auto-configuration must back off for user-defined beans, release its engine on context close, and must not automatically load or execute flows.

## Established workflow semantics

- A Markdown definition contains exactly one top-level fenced `mermaid` block.
- Supported headers are `flowchart TD` and `flowchart LR`.
- `start([label])` and `finish([label])` are reserved virtual endpoints.
- A rectangle is a business task backed by a Spring `FlowNode<O>` Bean. Business nodes should declare concrete output types; resolvers and graph storage use `FlowNode<?>`. Avoid raw FlowNode types except unavoidable class literals for container lookup. Runtime result storage remains heterogeneous and downstream reads retain runtime type checks.
- A task node ID without `_` is its Bean ID.
- For `beanId_alias`, the text before the first `_` is the Bean ID and the complete node ID is the invocation identity. Bean IDs use lower camel case and cannot contain `_`.
- A normal diamond with one incoming edge and multiple outgoing edges is an exclusive gateway. Its outgoing labels are restricted SpEL Boolean expressions; `default` is a reserved fallback label.
- Exactly one exclusive condition may match. Multiple matches fail the gateway. No match uses the single optional default edge; otherwise execution fails.
- A diamond labelled `X` is an exclusive merge and continues the selected branch.
- A diamond labelled `+` is a parallel split or join. A parallel join waits for all active incoming branches.
- A double-bordered rectangle is a synchronous child-flow call. Its target flow ID follows the same `_alias` rule.
- Child flows receive the parent input but have an isolated execution context. Their internal node results are not flattened into the parent.
- Flow-reference cycles and synchronous engine re-entry from a `FlowNode` are forbidden.
- Runtime scheduling is dependency-driven. Do not replace it with graph-level batching or layer barriers.
- A terminal timeout or failure must not be overwritten by a late worker result.

Do not silently broaden the Mermaid subset. Unsupported Mermaid syntax must fail during registration with a specific error code and source location where available.

## Implementation rules

- Preserve separation between parsing/compilation and runtime execution. Contract packages must not import implementation packages. Graph types must not depend on compiler/runtime types. Mutable compiler drafts stay package-private; published topology must be immutable.
- Compile and validate definitions at registration time; do not defer deterministic definition errors to execution.
- Keep execution state isolated per invocation. The same singleton Bean may be invoked concurrently and must not carry flow state.
- Never hold the root coordinator lock while executing user Bean code or evaluating external work.
- Child-flow coordination must be event-driven and must not block a worker thread waiting for another worker.
- Resource limits and deadlines must remain bounded and configurable.
- SpEL is a routing language, not a business-action language. Keep it read-only and deny constructors, type access, Bean access, method calls, assignment, and non-ancestor result access.
- Prefer explicit error codes over parsing exception messages in tests and public behavior.
- Public API changes require corresponding documentation and tests.

## Documentation requirements

- Use Chinese Javadoc for named production types, including nested compiler and runtime types. Describe responsibility, lifecycle, thread safety and important limitations instead of repeating the type name.
- Document public methods and constructors, parameters, return/null behavior and meaningful error codes. Overrides may inherit a documented interface contract and add implementation limits.
- Document record components with @param and explain shallow versus deep immutability. Explain configuration units, defaults and valid ranges.
- Explain scheduler locks, state transitions, physical task exit, cancellation and branch propagation where the implementation is non-obvious. Do not claim behavior the code does not provide.
- Keep package-info.java aligned with package responsibility. Review annotations alongside behavior changes.

## Testing expectations

Run before submitting changes:

```bash
mvn test
```

For release-oriented changes, also run:

```bash
mvn verify
```

Add tests for every behavior change. Relevant coverage includes:

- valid serial, parallel, conditional, alias, and child-flow execution;
- malformed Markdown/Mermaid and invalid graph structures;
- Bean binding and Spring proxy behavior;
- condition conflicts, defaults, forbidden SpEL, and ancestor visibility;
- failures, node/flow timeouts, cancellation, interruption, and late completion;
- bounded queues, admission rejection, one-worker child flows, and concurrent execution isolation;
- registration atomicity, engine shutdown, and flow-reference validation.

Run `python3 scripts/check-coverage.py` after `mvn verify`. CI enforces aggregate line coverage >= 95% and branch coverage >= 88% across core, Spring and Starter; examples contribute execution data but not production class counts. Do not lower thresholds to make failures pass. Keep `docs/testing-coverage.md` aligned with verified scenarios and explicitly label remaining gaps.

Concurrency tests should use latches/barriers and bounded waits; always release blocked tasks and shut down callers in finally blocks. Negative tests should assert error codes and observable side effects, not only that execution failed. Generated tests must use fixed seeds and an independent expected-result calculation.

Do not treat test count alone as evidence of completeness. For scheduler or parser changes, consider race-focused tests, stress tests, and fuzz/property-based tests.

## Change discipline

- Read `docs/requirements.md` and `docs/technical-design.md` before changing workflow semantics.
- Keep changes narrowly scoped and preserve unrelated user work.
- Do not introduce a dependency when the behavior can be implemented clearly with the JDK or an existing project dependency.
- Avoid compatibility layers for behavior that has never been released unless explicitly requested.
- Update `README.md` when user-facing syntax or setup changes.

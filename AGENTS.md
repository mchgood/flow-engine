# AGENTS.md

## Project purpose

`flow-engine` is a lightweight, in-process Java framework that executes workflows defined by Mermaid flowcharts embedded in Markdown.

Keep the project framework-only. Do not add a database, web console, visual editor, distributed scheduler, durable execution, approval system, or deployment platform unless the user explicitly changes the product scope.

## Technology and modules

- Java 17 or newer.
- Maven multi-module build.
- `flow-engine-core`: public API, Markdown/Mermaid compilation, graph validation, execution model, and DAG scheduler.
- `flow-engine-spring`: Spring Bean resolution and restricted SpEL evaluation.
- `flow-engine-examples`: executable usage examples and integration tests.
- `docs`: requirements and technical design. Keep both documents synchronized with semantic changes.

The core module must not depend on Spring. Spring-specific code belongs in `flow-engine-spring`.

## Established workflow semantics

- A Markdown definition contains exactly one top-level fenced `mermaid` block.
- Supported headers are `flowchart TD` and `flowchart LR`.
- `start([label])` and `finish([label])` are reserved virtual endpoints.
- A rectangle is a business task backed by a Spring `FlowNode` Bean.
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

- Preserve separation between parsing/compilation and runtime execution.
- Compile and validate definitions at registration time; do not defer deterministic definition errors to execution.
- Keep execution state isolated per invocation. The same singleton Bean may be invoked concurrently and must not carry flow state.
- Never hold the root coordinator lock while executing user Bean code or evaluating external work.
- Child-flow coordination must be event-driven and must not block a worker thread waiting for another worker.
- Resource limits and deadlines must remain bounded and configurable.
- SpEL is a routing language, not a business-action language. Keep it read-only and deny constructors, type access, Bean access, method calls, assignment, and non-ancestor result access.
- Prefer explicit error codes over parsing exception messages in tests and public behavior.
- Public API changes require corresponding documentation and tests.

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

Do not treat test count alone as evidence of completeness. For scheduler or parser changes, consider race-focused tests, stress tests, and fuzz/property-based tests.

## Change discipline

- Read `docs/requirements.md` and `docs/technical-design.md` before changing workflow semantics.
- Keep changes narrowly scoped and preserve unrelated user work.
- Do not introduce a dependency when the behavior can be implemented clearly with the JDK or an existing project dependency.
- Avoid compatibility layers for behavior that has never been released unless explicitly requested.
- Update `README.md` when user-facing syntax or setup changes.

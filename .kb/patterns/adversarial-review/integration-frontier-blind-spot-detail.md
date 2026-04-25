# Integration-frontier blind spot — detail

> Companion to `integration-frontier-blind-spot.md`. Extended analysis,
> non-fix patterns, and detection signals.

## Why per-WU TDD cannot see this

- **Tests use fakes, not the production assembly.** A serializer test
  builds a `SchemaSerializer` directly; it does not exercise the path
  through `TrieSSTableReader.get(key, serializer)` because that path is
  owned by a different WU.
- **Construct ownership splits by domain, not by data flow.** The
  encryption-scope footer lives in `jlsm-core/sstable`; the dispatch
  gate lives in `jlsm-table`; the engine wiring lives in `jlsm-engine`.
  Each domain's WU is self-coherent. The data-flow path through the
  three of them — _which is the production path_ — is no domain's
  responsibility.
- **Stubs satisfy the test contract, not the integration contract.** A
  test that stubs `TrieSSTableWriter` to capture arguments proves WU-X
  computes the right arguments. It does not prove WU-Y *invokes* WU-X
  with those arguments in production. There is no test that exercises
  both real constructs along the production path.
- **Compilation is not a contract check.** A 3-arg constructor reference
  compiles even when a 5-arg builder is required by the spec; the spec
  requirement is a runtime invariant the compiler cannot enforce.
- **`@spec` annotations cite the requirement, but do not prove the
  citation reaches production.** A method may carry `@spec R3e` and
  faithfully implement the gate, while having zero production call
  sites — the annotation does not assert reachability from the entry
  point.

## Pattern statement (full conjunction)

When **all** of these are true, expect frontier bugs:

1. The work plan stages constructs by domain ownership (e.g. footer in
   WU-2, dispatch in WU-4, engine wiring in WU-3, serializer in WU-1).
2. Each WU's tests stub or directly-instantiate dependencies belonging
   to other WUs.
3. There is no end-to-end test that exercises a full production data-
   flow path (read → reader → readContext → serializer → dispatch gate;
   write → engine → writer factory → commit hook → footer).
4. The cross-construct wiring lives in a "glue" file (engine integration,
   factory definition, default-method override) that is not the primary
   construct of any WU.

Each WU passing its tests in isolation gives **zero evidence** that
production wires the pieces together correctly. The integration surface
is unverified.

## What is not the fix

- **More unit tests per WU.** Adding tests inside a WU does not change
  what the WU's test surface covers. The integration frontier is outside
  it by construction.
- **Removing stubs.** Per-WU TDD necessarily stubs the dependencies the
  WU does not own; eliminating stubs collapses the work-plan into a
  single WU and gives up the parallelism and isolation that motivated
  the decomposition.
- **`@spec` discipline alone.** Annotations document intent. They do
  not prove the annotated method is reachable from production entry
  points.
- **Compile-time type narrowing alone.** A required-by-spec parameter
  does not become required-by-compiler unless the spec is mechanically
  encoded into the type. The 3-arg `TrieSSTableWriter::new` compiled
  cleanly even though a builder with `commitHook` was the spec-mandated
  form.

## Detection signals during work-plan review

- Work plan splits constructs by domain (sstable, table, engine,
  serializer) but no WU is named "engine wiring" or "production
  pipeline integration".
- Tests for WU-N consistently stub WU-(N-1)'s output rather than
  consume it.
- A spec requirement is marked "the X gate enforces Y" but no WU's
  test exercises the gate from a production entry point — only the
  gate's own unit test exercises it.
- A factory or builder construct is the consumed-by, not the owned-by,
  of every WU. Nobody owns its production assembly.
- `@spec` annotations on a method, but call-site count outside the test
  source set is zero.

## Reachability check (mechanical)

For each `@spec` annotation that names a "gate" or "enforcement"
requirement (R3e, R10c step-N, etc.), grep production sources for
call sites of the annotated method:

```bash
# Count production call sites (excluding the method's own test class)
rg -l 'decryptWithContext\(' modules/*/src/main/java | wc -l
```

A count of zero is a frontier-blind-spot bug regardless of how
thorough the method's own unit tests are. The defence is unreachable.

## Cross-WU frontier inventory (work-plan template fragment)

When authoring a work plan, add a frontier inventory after the WU
list:

```
## Frontier Inventory

For each production data-flow path that spans multiple WUs, list:
  - the path (entry point → ... → terminal),
  - the WUs whose constructs are in the path,
  - the WU (or sub-task) that owns the end-to-end test.

Path | WUs in path | Frontier-test owner
-----+-------------+--------------------
read: engine.get → reader.get → readContext → serializer.deserialize(.,ctx) → dispatch.decryptWithContext | WU-3, WU-1, WU-4 | WU-3 (engine integration)
write: engine.insert → writerFactory(builder) → writer.commitHook → footer | WU-3, WU-2 | WU-3 (engine integration)
```

If any path has no frontier-test owner, add a wiring WU or expand
an existing WU's scope to include it. **Do not assume the audit
will find it** — that is correct as a backstop, but cheaper to
catch in the work plan.

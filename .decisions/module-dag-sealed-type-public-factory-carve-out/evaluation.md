---
problem: "module-dag-sealed-type-public-factory-carve-out"
evaluated: "2026-04-25"
candidates:
  - path: "self-contained — no KB entry"
    name: "A. Public static factory + non-exported package + package-private ctor"
  - path: "self-contained — no KB entry"
    name: "B. Co-locate caller in same package as impl (collapse public/internal split)"
  - path: "self-contained — no KB entry"
    name: "C. Module-export carve-out (export the .internal package qualified-to-self)"
  - path: "self-contained — no KB entry"
    name: "D. Public class + private ctor + reflection-based factory (lookup-based bridge)"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 2
  accuracy: 3
  operational: 1
  fit: 3
---

# Evaluation — module-dag-sealed-type-public-factory-carve-out

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: none directly — this is a Java/JPMS pattern decision,
  not a research-backed algorithmic choice. Evaluation is grounded in
  language-spec invariants (Java SE 25 access rules, JPMS exports
  semantics) and the project's existing ADRs:
  - `.decisions/table-handle-scope-exposure/adr.md` v2 (defines sealed `Table`)
  - `.decisions/sstable-footer-scope-format/adr.md`
  - `.decisions/engine-api-surface-design/adr.md`
- Spec text under evaluation: `.spec/domains/sstable/footer-encryption-scope.md`
  R8e-R8j (sealed Table, runtime defences, trusted-export carve-out).

## Constraint Summary

The decision must accommodate Java's per-package visibility rules while
preserving the spec's trust-boundary intent. The exported-package
boundary in `module-info.java` is the load-bearing security mechanism;
constructor visibility is intra-module hygiene. The cryptographic
backstop (HKDF scope binding) means a small relaxation of constructor
visibility is acceptable provided the module-export boundary stays
intact.

## Weighted Constraint Priorities

| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Pattern applies to a small, well-bounded set of sealed types across ~5 modules |
| Resources | 1 | No runtime cost in any candidate; weight is purely about compile/build complexity |
| Complexity | 2 | Pattern must be expressible in spec language and per-class Javadoc |
| Accuracy | 3 | Security boundary — spec intent must be preserved |
| Operational | 1 | No deployment/operational behaviour changes in any candidate |
| Fit | 3 | Must align with the existing JPMS layout and not force module-graph rewrites |

---

## Candidate A: Public static factory + non-exported package + package-private ctor

**KB source:** Pattern grounded in Java SE 25 access-control rules
(JLS §6.6) and JPMS qualified-exports semantics (JLS §7.7.1, JEP 261).

**Relevant sections read:** Java SE 25 SE Specification §6.6.1 (Access
to packages and reference types), §7.7 (Module declarations).

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Pattern scales to N sealed types — each independently declares a static factory. |
| Resources | 1 | 5 | 5 | Zero runtime cost; pure javac visibility check. |
| Complexity | 2 | 4 | 8 | Adds one factory method per construction surface; spec language stays clear. **Would be a 2 if:** factories proliferated to the point that ctor and factory shapes diverged silently and forced a separate "factory contract" spec. Today this is one method per ctor arity. |
| Accuracy | 3 | 5 | 15 | The non-exported package IS the trust boundary. External (no `--add-exports`) callers cannot reference the type at all — they cannot import it, cannot reference its static methods, cannot subclass it. The factory method's `public` modifier only matters intra-module. **Would be a 2 if:** the JPMS export boundary were ever relaxed (would also break R8h's threat model). |
| Operational | 1 | 5 | 5 | No build or deploy change. |
| Fit | 3 | 5 | 15 | Matches the existing `CatalogClusteredTable.forEngine(...)` shape already in the codebase; aligns with all other internal packages (e.g., `jlsm.bloom.hash`, `jlsm.wal.internal`). No module-info changes required. |
| **Total** | | | **53** | |

**Hard disqualifiers:** None.

**Key strengths for this problem:**
- Already validated by working code: `CatalogClusteredTable.forEngine(...)`
  ships and passes `./gradlew check`.
- JPMS export boundary is the load-bearing mechanism; constructor
  modifier is defence-in-depth.
- Aligns with R8h's trusted-export carve-out: factories are reachable
  intra-module by `ClusteredEngine` (sibling public package) and
  reachable in tests via `--add-exports`.

**Key weaknesses for this problem:**
- Spec text mandating "non-public constructor" must be re-phrased to
  account for sibling-public-package factories. Without this, a literal
  reading of R8f "non-public constructor" makes A look non-compliant.

---

## Candidate B: Co-locate caller in same package as impl

**KB source:** Java SE 25 §6.6.1 — package-private members reachable
from same package only.

**Relevant sections read:** §6.6.1.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 2 | 2 | Forces every public-facing engine class to live in the internal package or every internal impl to live in the public package. Doesn't scale across the 5+ modules. |
| Resources | 1 | 5 | 5 | Zero runtime cost. |
| Complexity | 2 | 2 | 4 | Demolishes the public/internal package boundary that the rest of the project uses for API discipline. |
| Accuracy | 3 | 3 | 9 | If the caller is moved into `internal`, it is no longer exported and the public API surface shrinks (loss of `ClusteredEngine` as a public type). If the impl is moved into the public package, it becomes externally subclassable — the trust boundary collapses. **Would be a 2 if:** any code outside the module needed to reference `ClusteredEngine` directly (which it does — that's the public API entry point). |
| Operational | 1 | 5 | 5 | No deploy change. |
| Fit | 3 | 1 | 3 | Disqualifying — `ClusteredEngine` is the public clustering entry point. Collapsing it into `jlsm.engine.cluster.internal` breaks `module-info.java` and every consumer. |
| **Total** | | | **28** | |

**Hard disqualifiers:** Forces either loss of `ClusteredEngine` from
the public API, or loss of trust boundary on `CatalogClusteredTable`.
Both are unacceptable.

**Key strengths for this problem:**
- No factory method needed — direct constructor call works.

**Key weaknesses for this problem:**
- Conflicts with the JPMS public-API design: every module already
  uses the `<module>` vs `<module>.internal` split deliberately.
- Cannot generalise — the constraint would force public APIs to live
  in non-exported packages, defeating their public role.

---

## Candidate C: Module-export carve-out (qualified-exports to self)

**KB source:** JEP 261 (Module System), JLS §7.7.1 (qualified exports).

**Relevant sections read:** JLS §7.7.1; JEP 261 "Restricted Exports."

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 3 | 3 | Adds one `exports ... to ...` line per sealed-type relationship; manageable at small N but verbose. |
| Resources | 1 | 5 | 5 | Zero runtime cost. |
| Complexity | 2 | 2 | 4 | JPMS does not support `exports X to self` — qualified exports require a target module. Workarounds (e.g. `exports jlsm.engine.cluster.internal to jlsm.engine`) don't apply because we're inside the **same** module. |
| Accuracy | 3 | 1 | 3 | **Disqualifying mismatch with Java semantics.** Cross-package access within the same module is governed by package-level access modifiers, not by `exports`. `exports` only governs inter-module access. So C does not solve the problem it claims to solve. |
| Operational | 1 | 5 | 5 | No deploy change. |
| Fit | 3 | 1 | 3 | Module-info syntax does not express "let sibling public package see ctor of internal package's class without making the ctor public." This is not a JPMS-level concern. |
| **Total** | | | **23** | |

**Hard disqualifiers:** JPMS `exports` does not control intra-module
access. The candidate is a category error — module-export is the
wrong mechanism for the problem.

**Key strengths for this problem:** None — premise is malformed.

**Key weaknesses for this problem:** Doesn't actually solve the
constraint it claims to address.

---

## Candidate D: Public class + private ctor + reflection-based factory

**KB source:** `java.lang.invoke.MethodHandles.privateLookupIn` semantics
(Java SE 25 API spec).

**Relevant sections read:** `MethodHandles` Javadoc, JEP 416 reflection.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 3 | 3 | Reflection bridge per type — works but verbose. |
| Resources | 1 | 2 | 2 | Reflection adds startup-time cost on every instantiation; class-loading and lookup caching mitigate but don't eliminate. |
| Complexity | 2 | 1 | 2 | Reflection is the project's explicit "outside the trust model" mechanism (R8f bullet 4, R8h carve-out). Using it for legitimate construction blurs the threat-model line that the spec was written to draw. |
| Accuracy | 3 | 2 | 6 | The static-analysis trust boundary becomes "this class uses reflection internally for legitimate construction; the same reflection mechanism is also the threat model for malicious construction." Reviewers cannot distinguish the two by inspecting code; auditors must trace lookup chains. |
| Operational | 1 | 4 | 4 | No deploy change but adds startup work. |
| Fit | 3 | 1 | 3 | Project policy (R8f bullet 4): reflection access to internals is explicitly out-of-model. Using reflection as the legitimate construction path inverts that posture. |
| **Total** | | | **20** | |

**Hard disqualifiers:** Conflicts with the project's existing
"reflection is the threat model" posture (R8f bullet 4 / R8h).

**Key strengths for this problem:** None.

**Key weaknesses for this problem:** Inverts the project's stated
threat model.

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| A. Public static factory + non-exported package + package-private ctor | 5 | 5 | 4 | 5 | 5 | 5 | **53** |
| B. Co-locate caller and impl | 2 | 5 | 2 | 3 | 5 | 1 | 28 |
| C. Module-export carve-out | 3 | 5 | 2 | 1 | 5 | 1 | 23 |
| D. Reflection-based factory | 3 | 2 | 1 | 2 | 4 | 1 | 20 |

## Preliminary Recommendation

**Candidate A — Public static factory + non-exported package +
package-private ctor.** A wins on every dimension and is already the
shipping pattern in `CatalogClusteredTable.forEngine(...)`. The
decision codifies it as the canonical pattern and amends specs that
mandate "non-public constructor" to acknowledge module-graph reality:
the **non-exported package** is the trust boundary; the **public
factory** is the legitimate construction surface; the **package-private
constructor** is the residual intra-module discipline.

## Risks and Open Questions

- **Spec amendment surface:** existing R8f text reads "non-public
  constructor"; future readers may treat that literally. The amendment
  should re-phrase: "Construction is gated by a non-exported package
  containing the canonical (package-private) constructor; sibling
  public packages within the same module reach the constructor only
  via a public static factory in the impl class."
- **Pattern proliferation:** as more sealed types are added across
  modules, factory-method count grows. Acceptable at current scale;
  revisit if a module has >5 sealed types each with multi-arity
  factories.
- **Test pattern guidance:** the existing R8h trusted-export carve-out
  permits in-tree test stubs to subclass via `--add-exports`. The factory
  pattern does not change this — tests can still extend via
  `--add-exports` and bypass the factory entirely.

## Falsification Results (Pass 1 — autonomous adversary)

### Challenged Scores

**A.Accuracy=5:** Could be a 4 or worse if an attacker has reflection
access (`--add-opens`) to the internal package — they can bypass the
package-private ctor regardless of the factory. **Verdict: holds.**
This is exactly the scenario the spec already declares out-of-threat-model
(R8f bullet 4 + R8h carve-out). The HKDF scope binding (R11) is the
cryptographic backstop. The 5 reflects the type-system layer; the
threat-model layer is documented separately and consistently.

**A.Complexity=4:** Could be a 3 if the factory's argument list
diverges from the constructor's (e.g., factory takes a builder while
ctor takes positional args), opening a gap where the factory
unintentionally adds defaulting logic that the ctor doesn't enforce.
**Verdict: holds with discipline.** The shipped code uses 1:1
delegation (factory immediately calls `new`); the spec amendment must
require this 1:1 shape.

### Strongest Counter-Argument

The strongest alternative is **B (co-locate)**. It "wins" only in the
hypothetical where `ClusteredEngine` is not a public API entry point.
Since it IS the public API entry point per `engine-api-surface-design`
and the existing `module-info.java` exports, the counter-argument
fails on existing decision constraints.

### Most Dangerous Assumption

**The exported-package boundary is the trust boundary.** If the
project ever loosens `module-info.java` to export `jlsm.engine.cluster.internal`
unconditionally, the factory's `public` modifier becomes externally
reachable and the trust boundary collapses. The mitigation is already
present: `module-info.java` review is part of the PR readiness
checklist (`.claude/rules/pr-process.md` indirectly via
`/update-module-docs`), and the spec's R8j explicitly forbids
production use of `--add-exports` for these packages.

### Missing Candidates

**E. Inner-class factory:** put `ClusteredEngine` as a static nested
class of `CatalogClusteredTable`. Java's same-class private access
would let the engine see the ctor without exposing it via the package.
Rejected on inspection: this would force `ClusteredEngine` into
`jlsm.engine.cluster.internal`, the same outcome as B (loss of public
API), without B's structural simplicity.

**F. Module-private "friend" record carrying construction tokens:**
`Engine` constructs an unforgeable token record (sealed, package-private
to the engine's internal package) that `CatalogClusteredTable`'s
constructor accepts; only token holders can construct. Rejected on
inspection: adds runtime allocation per construction and a token-class
hierarchy parallel to the sealed type hierarchy. Token records would
themselves face the same module-graph constraint they were introduced
to solve. Higher complexity, no accuracy gain over A given the HKDF
backstop.

No additional candidates change the ranking.

## Deliberation Summary (autonomous mode)

Per the architect protocol's autonomous-deliberation directive in this
session: with A scoring 53 vs. nearest competitor 28, and B/C/D each
having a hard disqualifier or category error, no genuine multi-option
deliberation is required. The recommendation is **Candidate A** with
the spec-amendment guidance below.

**Confidence: High.** Six constraints fully specified; pattern is
already running in production code (`CatalogClusteredTable.forEngine`
ships, tests pass); HKDF cryptographic backstop covers the most
dangerous assumption; falsification surfaced no missing candidates
that change ranking.

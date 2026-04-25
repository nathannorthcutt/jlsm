# table-handle-scope-exposure — Decision Log

## 2026-04-24 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Evaluating how the encryption read path in jlsm-core obtains `(tenantId, domainId, tableId)` scope from a `Table` handle in jlsm-engine, subject to the module graph (jlsm-engine → jlsm-core) and backward-compat with pre-encryption tables.

**Files written/updated:**
- `constraints.md` — constraint profile dominated by fit/complexity; scale/resources N/A at this layer
- `evaluation.md` — five candidates: A (Table.scope), B (TableMetadata optional scope), C (package-private SPI, disqualified), D (EncryptionContext at createTable), E (catalog-mediated, disqualified)

---

## 2026-04-24 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Candidate G (TableMetadata extended with Optional<EncryptionMetadata> sub-record) confirmed. G emerged from falsification as a refinement of B — sub-record composition addresses the fit-coupling concern that a flat `Optional<TableScope>` creates. Encryption is one-way; in-place disable is deferred.

### Deliberation Summary

**Rounds of deliberation:** 2
**Recommendation presented:** B (flat Optional<TableScope>) → G (Optional<EncryptionMetadata>) after falsification
**Final decision:** G (same as revised recommendation after falsification + lifecycle analysis)

**Topics raised during deliberation:**
- User asked: "what if we want to be able to encrypt a table after creation?"
  Response: added Lifecycle constraint dimension to scoring. B/G accommodate
  naturally via catalog metadata atomic rewrite; D (EncryptionContext at
  createTable) fights the scenario; F (sealed subtype) is operationally
  painful. G widened its lead with Lifecycle as a factor.
- User asked: "is that a one-way operation? i.e. can you remove decryption later"
  Response: enumerated options — (1) no native disable, user does table
  copy + drop; (2) in-place disable with DRAINING state machine; (3)
  defer entirely. Noted industry precedent (CockroachDB, TiKV, MySQL
  InnoDB TDE, MongoDB CSFLE all either don't support in-place disable or
  implement it as copy-under-the-hood). Recommended deferral.
  User confirmed: encryption is one-way.

**Constraints updated during deliberation:**
- Added Lifecycle dimension (weight 2) after the post-creation-encryption
  question, widening G's margin over D/F.

**Assumptions explicitly confirmed by user:**
- Encryption is a one-way operation (no in-place disable)
- Option G is the chosen approach
- Runtime-check responsibility for "encryption empty but decryption
  attempted" is acceptable (vs. paying for a sealed-subtype refactor
  that would enforce it at the type level)

**Override:** None
**Override reason:** N/A

**Falsification outcome:**
- B's scores rescored: Complexity 5→4 (record evolution cost),
  Accuracy 5→4 (Optional<TableScope> foot-gun), Fit 5→4 (coupling),
  Operational 5→4 (no fail-fast at createTime). B revised total: 54.
- Missing candidate G surfaced: `Optional<EncryptionMetadata>`
  sub-record; scored 57 (vs B's revised 54) — won on Fit by separating
  descriptive metadata from security identity.
- Alternative F (sealed subtype) evaluated: 45 — refactor cost too
  high for the incremental invariant it provides.
- Recommendation changed B → G; reaffirmed after lifecycle analysis.

**Confirmation:** User confirmed with: "I think encryption should be one way and option G is the best"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — no changes after confirmation
- `evaluation.md` — updated in-session with falsification + lifecycle adjustments

**KB files read during evaluation:**
- None directly — this is an API-shape decision dominated by jlsm's
  existing Engine/Table/Catalog surface rather than general-industry
  research

**Related ADRs surveyed:**
- [`.decisions/engine-api-surface-design/adr.md`](../engine-api-surface-design/adr.md)
- [`.decisions/table-catalog-persistence/adr.md`](../table-catalog-persistence/adr.md)
- [`.decisions/three-tier-key-hierarchy/adr.md`](../three-tier-key-hierarchy/adr.md)
- [`.decisions/sstable-footer-scope-format/adr.md`](../sstable-footer-scope-format/adr.md)

---

## 2026-04-24 — revision-confirmed (v2)

**Agent:** Architect Agent
**Event:** revision-confirmed
**Summary:** Pass 2 falsification of dependent spec `sstable.footer-encryption-scope` (F1, reader trust boundary) demonstrated that the v1 runtime-check approach does not defend against a caller passing a non-catalog-mediated `Table` implementation. User confirmed revision to seal `Table` with a single permitted internal subtype `CatalogTable` — type-level enforcement of the trust boundary.

### Deliberation Summary

**Rounds of deliberation:** 1 (revision)
**Triggered by:** Pass 2 falsification F1 finding on sstable.footer-encryption-scope
**Final decision:** v1 (sub-record composition) + sealed Table with `permits jlsm.engine.internal.CatalogTable`

**Topics raised during deliberation:**
- User rejected the "small vs correct" framing: "I dont want small or deferred, I want correct."
- v1's candidate F rejection was reassessed: the original "refactor cost" was scored against a PUBLIC-split variant (EncryptedTable vs UnencryptedTable). The narrower variant adopted here — single permitted internal subtype — has none of the ergonomic costs the v1 rejection cited.
- Attack surface clarified: public extensibility of `Table` IS the attack surface. F1's falsification made the score imbalance concrete.

**Constraints updated during deliberation:** None.

**Assumptions explicitly confirmed by user:**
- Type-level enforcement of the trust boundary is worth the small refactor

**Override:** None
**Override reason:** N/A

**Confirmation:** User confirmed with: "F1 I want the sealed type, its more secure"

**Files written after revision:**
- `adr.md` — bumped v1 → v2, added Revision 2 section, updated implementation guidance + files list
- `evaluation.md` — unchanged (scoring adjustment recorded only in ADR revision narrative)

---

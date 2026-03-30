## 2026-03-20 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Problem directory and constraints.md written for discovery SPI design.

**Files written/updated:**
- `constraints.md` — initial constraint profile

**KB files read:**
- None — API design decision

---

## 2026-03-20 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Minimal Seed Provider with Optional Registration confirmed. User challenged missing self-registration for unmanaged environments, revised to include default no-op register/deregister methods.

### Deliberation Summary

**Rounds of deliberation:** 2
**Recommendation presented:** Minimal Seed Provider (no registration)
**Final decision:** Minimal Seed Provider with Optional Registration *(revised after user challenge)*

**Topics raised during deliberation:**
- User challenged lack of self-registration: "if you are doing your own clustering with some VMs that are just on a VPC, how do I try to announce?"
  Response: Added optional register/deregister as default methods. Managed environments ignore them, unmanaged environments override them.
- User confirmed: "it needs to be there for us to be useful outside of managed infrastructure environments"

**Constraints updated during deliberation:**
- Added: self-announcement is required for unmanaged infrastructure environments

**Assumptions explicitly confirmed by user:**
- Optional registration via default methods is the right pattern
- Stale registrations from crashes are acceptable (Rapid handles liveness)

**Override:** None — recommendation revised based on valid challenge
**Confirmation:** User confirmed with: "confirm"

**Files written after confirmation:**
- `adr.md` — decision record v1

**KB files read during evaluation:**
- None — API design decision

---

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Items:** continuous-rediscovery, discovery-environment-config, authenticated-discovery, table-ownership-discovery
**Summary:** 4 out-of-scope items promoted to tracked deferred decisions during /curate session.

---

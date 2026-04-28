---
{
  "id": "engine.handle-lifecycle.lifetime-policy",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "engine"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "parent_spec": "engine.handle-lifecycle",
  "_split_from": "engine.handle-lifecycle"
}
---

# engine.handle-lifecycle.lifetime-policy — Idle Timeout and Max Lifetime

This spec was carved from `engine.handle-lifecycle` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R11. The engine must support a configurable idle timeout duration. The idle timeout measures the elapsed time since the handle last completed a data operation (transitioned from ACTIVE to IDLE).

R12. The idle timeout must be configurable at engine build time with a default of disabled (no idle timeout).

R13. A handle that has been IDLE for longer than the configured idle timeout must be transitioned to EXPIRED with reason IDLE_TIMEOUT.

R14. A handle in the OPEN state (never used) must be eligible for idle timeout based on its creation time.

R15. The engine must periodically scan for idle handles. The scan interval must be configurable at engine build time.

R15a. When the scan interval is not explicitly configured and the idle timeout is enabled, the default scan interval must be one-tenth the idle timeout duration, with a minimum of 100 milliseconds.

R15b. When the idle timeout is disabled and no scan interval is explicitly configured, the engine must not run idle scans.

R16. The idle timeout scan must not hold a global lock for the entire scan duration. The scan must be non-blocking with respect to handle creation and data operations.

R16a. If the idle scan selects a handle for expiry and that handle has concurrently transitioned to ACTIVE, the scan must skip that handle without error. The ACTIVE handle must not be expired.

### Max lifetime (TTL)

R17. The engine must support a configurable maximum lifetime duration. The maximum lifetime measures the elapsed time since the handle was created, regardless of usage.

R18. The maximum lifetime must be configurable at engine build time with a default of disabled (no maximum lifetime).

R19. A handle whose age exceeds the configured maximum lifetime must be transitioned to EXPIRED with reason MAX_LIFETIME.

R20. The engine must apply per-handle jitter to max-lifetime expiry times to prevent mass expiration of handles created in a burst. Handles created within the same wall-clock second must not all expire at the same instant.

R21. The jitter value for a handle must be determined at handle creation time and must not change for the lifetime of that handle.

R22. A handle in the ACTIVE state must not be expired by max-lifetime until it transitions to IDLE. The expiry must be deferred, not skipped.

R22a. When a deferred max-lifetime expiry fires on a handle that has transitioned to IDLE, the handle must be expired immediately. The deferred expiry must not require waiting for the next scan interval.

### Priority levels



---

## Notes

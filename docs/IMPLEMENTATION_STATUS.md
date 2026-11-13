# Implementation status: six delivery tasks

This register is intentionally candid. A green Android build is not proof of accounting correctness.

## 1. Member management and Supervisor activation
- [x] Server-side prototype supports member identity updates with an audit event.
- [x] Inactivity status can be calculated from last successful payment and a configurable threshold.
- [x] Reactivation is restricted to the Supervisor role in the prototype.
- [ ] Add member search/edit/freeze/reactivate screens to both Android apps.
- [ ] Persist activation decisions through authenticated server sync; Collector must not be able to override them.

## 2. Arrears and penalty accounting
- [x] Prototype records penalties against a due Saturday and explicit configuration version.
- [x] Duplicate penalty posting is rejected.
- [x] Penalty reversals and fine settlements are append-only events.
- [ ] Complete due-obligation generation, historical rule-version selection for every missed Saturday, waivers, and full balance reconciliation.
- [ ] Expose balances and penalty breakdown in Android UI.

## 3. Operational restoration
- [x] Prototype creates a lean 12-month Collector snapshot with member statuses, active config, recent events and future allocations.
- [ ] Add authenticated restore-preview/confirm API and atomic Android restore.
- [ ] Preserve/export pending local events before replacing local storage.
- [ ] Include server event cursor, integrity verification, and recovery of all unpaid obligations/penalties.
- [ ] Add long-term inactive exception counts and restore preview confirmation in Android UI.

## 4. Report reconciliation and corrections
- [x] Prototype uses unique member/Saturday allocation keys.
- [x] Payment reversals are append-only and release allocations.
- [ ] Add globally stable event IDs, server acknowledgements, conflict resolution and idempotent upload endpoints.
- [ ] Replace checksum-only trust in manual JSON exchange with authenticated device/operator signatures.
- [ ] Add correction/reversal UI with mandatory reason and supervisor authorization.

## 5. Automated financial tests
- [x] Added Python unit tests for weekly/advance payments, duplicates, penalties, reversals, role-restricted reactivation and restore packages.
- [ ] Add property-based and edge-case tests, Kotlin unit/instrumentation tests, and integration tests against a deployed backend.
- [ ] Verify all build/test jobs on GitHub Actions before release.

## 6. Release APKs
- [ ] Run the Android build and Python tests on this branch.
- [ ] Add release signing through protected GitHub secrets; do not commit signing keys.
- [ ] Install and test both apps on real Android devices, including offline/restore workflows.
- [ ] Publish versioned release APKs only after financial acceptance tests pass.

## Architecture blockers
The repository still has no deployed authenticated backend or server API. The new Python module is a testable accounting/recovery foundation only; it is not connected to the Android apps and must not be treated as production accounting. TLS, operator/device authentication, encrypted backups, event cursors, server-side append-only storage, monitoring and tested disaster recovery remain required.

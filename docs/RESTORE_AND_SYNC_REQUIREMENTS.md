# Server synchronization, reinstall recovery, and monthly restoration requirements

## Architecture decision

The existing JSON file exchange is a manual offline transfer mechanism. It is not a main server and it cannot restore data that exists only on a lost or reinstalled Collector phone. The production design must add a central, authenticated backend as the authoritative backup and synchronization service. The system still has exactly one Collector and one Supervisor; do not introduce multiple collector accounts or collector assignment.

The apps must continue to work offline. They queue locally created events and synchronize them when internet connectivity returns. The backend stores the authoritative append-only financial ledger and versioned configuration. Manual encrypted export/import remains a fallback, not the primary backup.

## Collector: full recovery after reinstall or phone replacement

On a newly installed Collector, the authorized operator must sign in / register the replacement device and request **Restore Collector**. This is an **operational restore**, not a five-year historical archive download. The backend must return a compact, authoritative snapshot containing:

- Fund identity and active configuration, including configuration version and effective date.
- The complete member identity/ID register and each member's operational status, including last successful payment date and current arrears/outstanding balance. This is necessary to identify long-inactive members without downloading years of routine payment records.
- The rolling **most recent 12 months** of payment transactions and weekly Saturday allocations, sufficient to resume current collection and reconcile recent activity.
- Every outstanding obligation and every future installment already covered by an advance payment, even if the original advance transaction is older than 12 months.
- All currently relevant penalties, waivers, reversals/corrections and audit references needed to calculate the present position. Historical items outside the 12-month window remain on the server and can be retrieved by the Supervisor for audit; they are not routinely pushed to the Collector.
- Last accepted synchronization cursor / event sequence and integrity metadata.

### Inactive-member handling during operational restore

- A member with no successful installment for more than **6 consecutive months** is marked **Frozen / Inactive** in the restored Collector register by default. The threshold must be configurable by the Supervisor; the 6-month threshold is the initial default.
- Frozen and long-term inactive members remain searchable in a separate inactive list with only operationally necessary details: ID, name, last successful payment date, missed installments, calculated outstanding fine, total outstanding balance and status. Their identity records must not be deleted or their IDs reused.
- **Only the Supervisor can reactivate** a frozen or long-term inactive member. The Collector cannot reactivate them or accept routine contributions for them until the Supervisor records the reactivation.
- On reactivation, the member must settle the outstanding fine/arrears calculated under the **same general penalty rules used for regular members**. There is no separate inactive-member penalty rate or arbitrary reactivation fee. Apply the rule/version and grace period that governed each missed due date; do not retroactively impose a newly changed rule. Record any payment, waiver or correction as an auditable ledger event.
- Reactivation must preserve the member's full prior financial position and must not erase unpaid obligations.
- The restore must also flag members with no successful installment during the entire 12-month lookback as long-term inactive exceptions. They must be included in the restored register/status snapshot even though their older routine transaction history is not downloaded.
- Do not infer that a member has paid merely because a transaction exists: only a valid, accepted installment allocation counts. Reversed or voided allocations do not reset the inactivity clock.
- The app must show the lookback period, inactivity threshold, count of active members and count of frozen/long-term inactive members before the operator confirms the restore.

The Collector must validate the package, show a restore preview, and commit it atomically. It must not begin normal collection until the restore has completed and the active rules and ledger state are verified. Existing local unsynchronized events must be exported/recovered before replacing local data; never silently overwrite them.

### Operational-data-only restore (avoid junk)

The Collector restore must be deliberately lean. Include only data required to identify members, determine who can pay, collect the correct installment, prevent duplicate allocations, calculate current obligations, and continue synchronization: current member roster/status; stable member IDs; last successful installment date; current arrears and penalty totals; the rolling 12-month transaction/allocation history; outstanding and future advance allocations; active rule values/version/effective dates; and the last acknowledged sync cursor. Include the minimum transaction references needed to trace corrections and reversals.

Do **not** routinely download the full all-time ledger, old receipt images, duplicate report files, verbose diagnostic logs, obsolete configuration copies, or unrelated historical attachments to the Collector. Keep full historical records securely on the server for Supervisor audit and targeted retrieval when needed. The restore preview must show the package date, lookback period, record counts and data categories so the operator can reject an unexpectedly large or irrelevant package.

A restore must preserve all existing member IDs. Do not regenerate IDs after reinstall. ID allocation must be server-coordinated or based on a reserved ID range so a replacement device cannot accidentally reuse an existing ID.

## Supervisor: month-scoped restore and reporting

The Supervisor must have a **Restore by month** function. The operator chooses a year and month, then the app downloads/restores that month's complete accounting package, including:

- All transactions recorded in the selected month.
- Payments and their Saturday allocations, including advance payments made in that month and the future Saturdays they cover.
- Penalties, waivers, reversals and corrections recorded in that month.
- Member identity references needed to interpret those transactions.
- The applicable configuration version and effective dates.
- Opening balances carried into the month and closing balances carried out of it.
- Report totals, transaction counts, integrity/checkpoint data and restore timestamp.

The month package must be idempotent: restoring it twice must not duplicate transactions or allocations. The Supervisor must be able to choose another month later. Monthly restoration is a view/archive operation and must not delete the all-time server ledger. The Supervisor should also have a full-system recovery option for a new/replaced Supervisor phone.

## Synchronization and accounting invariants

- Each transaction and each member + Saturday allocation has a globally unique stable key.
- Server accepts an event only once; repeat uploads return the prior acknowledgement.
- Advance payments are single transactions with explicit allocation rows for each complete future weekly installment, up to 26 weeks.
- A member cannot have two payment allocations for the same Saturday.
- Penalties are explicit ledger events linked to the applicable due Saturday and configuration version. New rules must not silently recalculate historical penalties.
- Corrections are reversals/adjustments with actor, timestamp, reason and link to the original transaction; do not edit or erase posted financial records.
- Server records acknowledgements/cursors so the Collector knows which events are safely backed up.
- TLS in transit, authenticated operator/device access, encrypted server backups, access logs and tested restore procedures are required. A plain SHA-256 checksum is not authentication or proof of who sent a file.
- If the Collector is offline, it may continue according to a documented offline policy, but events not yet synchronized to the server cannot be recovered from the server if that phone is lost. The app must clearly show the last successful backup time and pending unsynchronized count. Until the backend is deployed, daily transfer to the Supervisor and an independent backup are necessary safeguards.

## Update package is broader than rules

The Collector's **Update / Restore** action must not only import installment/grace/penalty settings. It must support:
1. Fetch latest configuration and effective date.
2. Fetch new member/register changes and authoritative member-ID state.
3. Fetch missing ledger events, payment allocations, penalties and corrections.
4. Resume from last acknowledged event cursor without duplicating data.
5. Perform full bootstrap restore after reinstall or when local data is missing/corrupt.

The Supervisor's update/report tools must likewise sync its month archives and the full ledger as authorized.

## Current implementation status

The current Android prototype uses local SharedPreferences and manual JSON file transfer. It does not yet have a hosted backend, authenticated server sync, complete Collector bootstrap restore, or Supervisor month-scoped server restore. Those features must be implemented and tested before the apps are relied on for financial operations.

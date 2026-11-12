# Village Trust Fund Android apps

Two Android application modules:
- **VT Collector** — used by one collector on one device.
- **VT Supervisor** — used by one supervisor on one device.

## Operating model
This is a one-collector / one-supervisor system. Do not build multi-collector assignment, collector-ID allocation, or multi-collector synchronization. The Collector generates each member's unique 4-digit ID locally at registration. The ID is included in reports sent to the Supervisor and must remain unchanged during transfer.

## Member registration and lookup
- Registration requires the member's full name written in Amharic (Ethiopic script).
- Generate a unique 4-digit member ID automatically; prevent reuse within the member register.
- The Collector must support searching for an existing member by either name or ID before recording a payment.
- If names are ambiguous, require selection by member ID.

## Weekly contribution and accounting rules
- Installments are **weekly**, with **Saturday** as the regular collection date. They are not monthly.
- A member may have only one payment allocation for any given Saturday.
- Reject duplicate payments for the same member and Saturday, including attempts to pay twice on the same collection day.
- Advance payment is allowed only for complete weekly installments, up to six months ahead (maximum 26 weekly installments).
- An advance transaction is recorded once and allocated to the selected future Saturdays. It must not create duplicate transaction entries or duplicate member/date allocations.
- Do not accept partial installments as advance payment.
- Every posted payment must have a unique transaction reference and be traceable in reports.

## Report transfer
- The Collector needs a real **Send Report** action to export its member and payment records for the Supervisor.
- The Supervisor needs a real **Receive/Import Report** action to load the exported report, validate it, identify duplicates/conflicts, and show reconciliation results.
- Imported records must be deduplicated by transaction reference and by member + Saturday allocation. A report must never silently double-count a payment.
- The workflow is offline file transfer between the two devices; do not assume a live server or internet connection.

## Important status
The Android apps are still prototype software and are not approved for live financial collection. A successful CI build only confirms compilation; it does not prove correct accounting, secure storage, reliable transfer, or device-level behavior. Before real use, implement and test durable storage, audit/correction handling, report validation, and the complete accounting rules.


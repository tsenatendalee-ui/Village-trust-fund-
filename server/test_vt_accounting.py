import unittest
from datetime import date, timedelta
from vt_accounting import TrustFund, RuleError


class AccountingTests(unittest.TestCase):
    def setUp(self):
        self.f = TrustFund()
        self.f.set_config(1, 100, 7, 10, "2026-01-01", 6)
        self.f.register_member("VT-0001-8", "Alem", "2026-01-01")

    def tearDown(self):
        self.f.close()

    def test_weekly_payment_and_duplicate_allocation(self):
        self.f.record_payment("VT-0001-8", "2026-01-03", 2, "2026-01-03", "collector")
        with self.assertRaisesRegex(RuleError, "Duplicate"):
            self.f.record_payment("VT-0001-8", "2026-01-10", 1, "2026-01-10", "collector")

    def test_advance_is_single_transaction_and_capped(self):
        eid = self.f.record_payment("VT-0001-8", "2026-01-03", 3, "2026-01-03", "collector")
        event = self.f.db.execute("SELECT * FROM events WHERE event_id=?", (eid,)).fetchone()
        self.assertEqual(event["amount"], 300)
        with self.assertRaises(RuleError):
            self.f.record_payment("VT-0001-8", "2026-01-03", 26, "2026-01-03", "collector")

    def test_penalty_uses_selected_rule_version_and_is_idempotent(self):
        self.f.post_penalty("VT-0001-8", "2026-01-03", "2026-01-10", "supervisor", 1)
        with self.assertRaisesRegex(RuleError, "already posted"):
            self.f.post_penalty("VT-0001-8", "2026-01-03", "2026-01-10", "supervisor", 1)

    def test_reversal_is_append_only_and_releases_allocation(self):
        eid = self.f.record_payment("VT-0001-8", "2026-01-03", 1, "2026-01-03", "collector")
        self.f.reverse_event(eid, "supervisor", "wrong member selected", "2026-01-04")
        self.assertEqual(self.f.db.execute("SELECT COUNT(*) FROM events").fetchone()[0], 2)
        self.assertEqual(self.f.db.execute("SELECT COUNT(*) FROM allocations").fetchone()[0], 0)

    def test_only_supervisor_can_reactivate(self):
        self.f.db.execute("UPDATE members SET status='FROZEN' WHERE member_id='VT-0001-8'")
        self.f.db.commit()
        with self.assertRaisesRegex(RuleError, "Only the Supervisor"):
            self.f.reactivate("VT-0001-8", "COLLECTOR", "collector", 0,
                              "2026-01-10", "test")
        result = self.f.reactivate("VT-0001-8", "SUPERVISOR", "supervisor", 0,
                                   "2026-01-10", "verified")
        self.assertEqual(result["status"], "ACTIVE")

    def test_restore_is_operational_and_month_package_is_idempotent_shape(self):
        self.f.record_payment("VT-0001-8", "2026-01-03", 2, "2026-01-03", "collector")
        snap = self.f.operational_snapshot("2026-01-05")
        self.assertEqual(snap["lookback_months"], 12)
        self.assertEqual(snap["record_counts"]["members"], 1)
        month = self.f.supervisor_month_package(2026, 1)
        self.assertEqual(month["event_count"], 1)
        self.assertEqual(self.f.supervisor_month_package(2026, 1)["event_count"], 1)


if __name__ == "__main__":
    unittest.main()

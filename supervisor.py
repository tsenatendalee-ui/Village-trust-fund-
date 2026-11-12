
"""Village Trust Fund - supervisor-side logic (prototype).

Receives collector reports, checks them, keeps the master record,
tracks config updates until the collector acknowledges them, and
builds the daily summary. Depends on vt_core.py and vt_protocol.py.
"""
import datetime as dt
import json

from vt_protocol import (build_report_ack, build_update, check_entry_chain,
                         check_sequence, verify)

CASH_TOLERANCE = 0.005   # birr; any larger difference raises an alert


class Supervisor:
    def __init__(self, key: bytes):
        self.key = key
        self.master = []             # accepted entries, in order, as the collector signed them
        self.last_report_seq = 0     # last collector report accepted
        self.out_seq = 0             # our own outgoing message counter
        self.pending_updates = {}    # config version -> message awaiting acknowledgement
        self.alerts = []

    def _alert(self, text: str):
        self.alerts.append(text)

    # ---------- Collector -> supervisor ----------
    def receive_report(self, raw_parts: list[str]) -> dict:
        """Process one report (possibly split into parts). Returns the ack to send back."""
        envs = [json.loads(p) for p in raw_parts]
        if not all(verify(self.key, e) for e in envs):
            self._alert("Report rejected: signature failed (possible tampering).")
            return {"ack": None, "accepted": 0}

        head = envs[0]
        seq_state = check_sequence(self.last_report_seq, head)
        if seq_state == "replay":
            self._alert(f"Duplicate report {head['seq']} ignored.")
            return {"ack": None, "accepted": 0}
        if seq_state == "gap":
            self._alert(f"Report {head['seq']} arrived out of order. Expected {self.last_report_seq + 1}.")
            return {"ack": None, "accepted": 0}

        entries = []
        for e in sorted(envs, key=lambda x: x["body"].get("part", 1)):
            entries.extend(e["body"]["entries"])

        prev_hash = self.master[-1][7] if self.master else "GENESIS"
        broken = check_entry_chain(prev_hash, entries)
        if broken:
            self._alert(f"Report rejected: entry chain broken at {broken}.")
            return {"ack": None, "accepted": 0}

        body = head["body"]
        cash_diff = round(body["cash_declared"] - body["payments_total"], 2)
        if abs(cash_diff) > CASH_TOLERANCE:
            self._alert(f"Cash mismatch: collector declares {body['cash_declared']} birr, "
                        f"entries show {body['payments_total']} birr (difference {cash_diff}).")

        self.master.extend(entries)
        self.last_report_seq = head["seq"]
        self.out_seq += 1
        ack = build_report_ack(self.key, self.out_seq, "now", head["seq"],
                               accepted_upto=entries[-1][0] if entries else None, rejected=[])
        return {"ack": ack, "accepted": len(entries)}

    # ---------- Supervisor -> collector ----------
    def send_update(self, config: dict) -> dict:
        self.out_seq += 1
        msg = build_update(self.key, self.out_seq, "now", config)
        self.pending_updates[config["version"]] = msg
        return msg

    def resend_pending(self) -> list[dict]:
        """Messages to re-send to the collector until each one is acknowledged."""
        return list(self.pending_updates.values())

    def receive_update_ack(self, raw: str) -> bool:
        env = json.loads(raw)
        if not verify(self.key, env) or env.get("type") != "update_ack":
            self._alert("Update acknowledgement rejected: signature failed.")
            return False
        version = env["body"]["config_version"]
        if self.pending_updates.pop(version, None) is None:
            return False
        return True

    def pending_warning(self) -> str | None:
        if self.pending_updates:
            versions = ", ".join(str(v) for v in sorted(self.pending_updates))
            return f"Collector has not confirmed config version(s): {versions}."
        return None

    # ---------- Daily summary ----------
    def daily_summary(self, day: dt.date, all_members: list[str]) -> dict:
        prefix = day.isoformat()
        todays = [e for e in self.master if e[5].startswith(prefix)]
        paid = {e[2] for e in todays if e[1] == "payment"}
        penalties = [e for e in todays if e[1] == "penalty"]
        return {
            "date": day.isoformat(),
            "members_paid": len(paid),
            "members_unpaid": len([m for m in all_members if m not in paid]),
            "payments_total": round(sum(e[4] for e in todays if e[1] == "payment"), 2),
            "penalties_count": len(penalties),
            "penalties_total": round(sum(e[4] for e in penalties), 2),
            "alerts": list(self.alerts),
        }


# ---------- Self-check ----------
if __name__ == "__main__":
    from vt_core import Config, Ledger, accrue, make_member_id, record_payment
    from vt_protocol import build_report, build_update_ack

    key = b"demo-key-set-at-setup"
    cfg = Config(version=1)
    collector = Ledger()
    m1, m2 = make_member_id(1), make_member_id(2)

    record_payment(collector, cfg, m1, dt.date(2026, 11, 14), "2026-11-14T10:00Z")
    record_payment(collector, cfg, m2, dt.date(2026, 11, 14), "2026-11-14T10:05Z")
    accrue(collector, cfg, m1, dt.date(2026, 11, 22), "2026-11-22T06:00Z")

    def as_rows(ledger):
        return [[e.seq, e.kind, e.member, e.ref, e.amount, e.at, e.prev_hash, e.hash]
                for e in ledger.entries]

    sup = Supervisor(key)

    # 1. Honest report with correct cash: accepted, no alerts
    rows = as_rows(collector)
    parts = build_report(key, 1, "t", rows, cash_declared=200.0, first_entry_seq=0,
                         last_entry_seq=rows[-1][0])
    out = sup.receive_report([*parts])
    assert out["accepted"] == len(rows) and out["ack"] is not None
    assert sup.alerts == []

    # 2. Replay of the same report: ignored
    sup.receive_report(parts)
    assert any("Duplicate" in a for a in sup.alerts)

    # 3. Tampered report: rejected by signature
    sup2 = Supervisor(key)
    bad = json.loads(parts[0])
    bad["body"]["cash_declared"] = 999.0
    sup2.receive_report([json.dumps(bad)])
    assert any("signature failed" in a for a in sup2.alerts)

    # 4. Cash short by 100: accepted, but the mismatch is alerted
    sup3 = Supervisor(key)
    short = build_report(key, 1, "t", rows, cash_declared=100.0, first_entry_seq=0,
                         last_entry_seq=rows[-1][0])
    sup3.receive_report(short)
    assert any("Cash mismatch" in a for a in sup3.alerts)

    # 5. Config update: pending until the collector acknowledges
    cfg2 = {"version": 2, "amount": 100.0, "period_days": 14, "anchor": "2026-11-14",
            "grace_days": 7, "penalty_mode": "fixed", "penalty_value": 10.0,
            "penalty_cap": None, "penalty_model": "per_period", "language": "en"}
    sup.send_update(cfg2)
    assert sup.pending_warning() is not None
    ack = build_update_ack(key, 1, "t", config_version=2, applied_at="2026-11-22T07:00Z")
    assert sup.receive_update_ack(json.dumps(ack))
    assert sup.pending_warning() is None

    # 6. Daily summary for 14 Nov
    s = sup.daily_summary(dt.date(2026, 11, 14), [m1, m2, make_member_id(3)])
    assert s["members_paid"] == 2 and s["members_unpaid"] == 1
    assert s["payments_total"] == 200.0

    print("Supervisor checks passed.")
    print("Daily summary:", json.dumps(s, ensure_ascii=False))

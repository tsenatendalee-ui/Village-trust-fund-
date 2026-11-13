"""Operational accounting foundation for Village Trust Fund.

This is a server-side prototype module, not yet wired to either Android app.
It deliberately stores append-only events and unique member/Saturday allocations.
All dates are ISO Gregorian dates; the Android presentation layer may show EC dates.
"""
from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import date, timedelta
from pathlib import Path
from typing import Iterable


class RuleError(ValueError):
    pass


class TrustFund:
    def __init__(self, database: str | Path = ":memory:"):
        self.db = sqlite3.connect(str(database))
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS members(
          member_id TEXT PRIMARY KEY, name TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'ACTIVE',
          joined_on TEXT NOT NULL, last_paid_on TEXT);
        CREATE TABLE IF NOT EXISTS configs(
          version INTEGER PRIMARY KEY, weekly_amount INTEGER NOT NULL,
          grace_days INTEGER NOT NULL, penalty_birr INTEGER NOT NULL,
          effective_from TEXT NOT NULL, inactive_months INTEGER NOT NULL DEFAULT 6);
        CREATE TABLE IF NOT EXISTS events(
          event_id TEXT PRIMARY KEY, kind TEXT NOT NULL, member_id TEXT NOT NULL,
          amount INTEGER NOT NULL, occurred_on TEXT NOT NULL,
          actor TEXT NOT NULL, reason TEXT NOT NULL DEFAULT '',
          ref_event_id TEXT, config_version INTEGER,
          payload TEXT NOT NULL DEFAULT '{}',
          FOREIGN KEY(member_id) REFERENCES members(member_id));
        CREATE TABLE IF NOT EXISTS allocations(
          member_id TEXT NOT NULL, due_saturday TEXT NOT NULL,
          event_id TEXT NOT NULL, amount INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1,
          FOREIGN KEY(event_id) REFERENCES events(event_id));
        CREATE UNIQUE INDEX IF NOT EXISTS one_active_member_due
          ON allocations(member_id,due_saturday) WHERE active=1;
        CREATE INDEX IF NOT EXISTS events_member_date ON events(member_id,occurred_on);
        """)
        self.db.commit()

    def close(self):
        self.db.close()

    def set_config(self, version: int, weekly_amount: int, grace_days: int,
                   penalty_birr: int, effective_from: str,
                   inactive_months: int = 6):
        if version < 1 or weekly_amount <= 0 or grace_days < 0 or penalty_birr < 0 or inactive_months < 1:
            raise RuleError("Invalid configuration values.")
        date.fromisoformat(effective_from)
        self.db.execute("""INSERT INTO configs
          (version,weekly_amount,grace_days,penalty_birr,effective_from,inactive_months)
          VALUES(?,?,?,?,?,?)""",
          (version, weekly_amount, grace_days, penalty_birr, effective_from, inactive_months))
        self.db.commit()

    def register_member(self, member_id: str, name: str, joined_on: str):
        if not member_id.strip() or not name.strip():
            raise RuleError("Member ID and name are required.")
        date.fromisoformat(joined_on)
        self.db.execute("INSERT INTO members(member_id,name,joined_on) VALUES(?,?,?)",
                        (member_id.strip(), name.strip(), joined_on))
        self.db.commit()

    def update_member_name(self, member_id: str, name: str, actor: str, reason: str):
        if not name.strip() or not actor.strip() or not reason.strip():
            raise RuleError("Name, actor and reason are required.")
        with self.db:
            cur = self.db.execute("UPDATE members SET name=? WHERE member_id=?",
                                  (name.strip(), member_id))
            if cur.rowcount == 0:
                raise RuleError("Member not found.")
            self._event("member_update", member_id, 0, date.today().isoformat(),
                        actor, reason, payload={"new_name": name.strip()})

    def _event(self, kind, member_id, amount, occurred_on, actor, reason="",
               ref_event_id=None, config_version=None, payload=None):
        event_id = str(uuid.uuid4())
        self.db.execute("""INSERT INTO events
          (event_id,kind,member_id,amount,occurred_on,actor,reason,ref_event_id,config_version,payload)
          VALUES(?,?,?,?,?,?,?,?,?,?)""",
          (event_id, kind, member_id, int(amount), occurred_on, actor, reason,
           ref_event_id, config_version, json.dumps(payload or {}, sort_keys=True)))
        return event_id

    @staticmethod
    def _saturdays(first: date, count: int) -> list[date]:
        return [first + timedelta(days=7*i) for i in range(count)]

    def record_payment(self, member_id: str, first_saturday: str, installments: int,
                       paid_on: str, actor: str) -> str:
        first = date.fromisoformat(first_saturday)
        paid = date.fromisoformat(paid_on)
        if first.weekday() != 5:
            raise RuleError("Installments must be allocated to Saturdays.")
        if not 1 <= installments <= 26:
            raise RuleError("A payment must cover 1–26 complete weekly installments.")
        last = first + timedelta(days=7*(installments-1))
        if last > paid + timedelta(days=183):
            raise RuleError("Advance allocation cannot extend beyond six months.")
        if not actor.strip():
            raise RuleError("Collector identity is required.")
        member = self.db.execute("SELECT * FROM members WHERE member_id=?",
                                  (member_id,)).fetchone()
        if member is None:
            raise RuleError("Member not found.")
        if member["status"] != "ACTIVE":
            raise RuleError("Only active members can pay; Supervisor reactivation is required.")
        cfg = self.db.execute("""SELECT * FROM configs WHERE effective_from<=?
          ORDER BY effective_from DESC,version DESC LIMIT 1""", (paid_on,)).fetchone()
        if cfg is None:
            raise RuleError("No effective fund configuration exists.")
        dates = self._saturdays(first, installments)
        with self.db:
            for due in dates:
                exists = self.db.execute("""SELECT 1 FROM allocations
                  WHERE member_id=? AND due_saturday=? AND active=1""",
                  (member_id, due.isoformat())).fetchone()
                if exists:
                    raise RuleError("Duplicate member/Saturday allocation: " + due.isoformat())
            eid = self._event("payment", member_id, cfg["weekly_amount"]*installments,
                              paid_on, actor, config_version=cfg["version"],
                              payload={"installments": installments,
                                       "allocation_dates": [d.isoformat() for d in dates]})
            for d in dates:
                self.db.execute("""INSERT INTO allocations
                  (member_id,due_saturday,event_id,amount,active) VALUES(?,?,?,?,1)""",
                  (member_id, d.isoformat(), eid, cfg["weekly_amount"]))
            self.db.execute("UPDATE members SET last_paid_on=? WHERE member_id=?",
                            (paid_on, member_id))
        return eid

    def post_penalty(self, member_id: str, due_saturday: str, posted_on: str,
                     actor: str, config_version: int, reason: str = "Missed installment"):
        due = date.fromisoformat(due_saturday)
        if due.weekday() != 5:
            raise RuleError("Penalty must reference a Saturday due date.")
        cfg = self.db.execute("SELECT * FROM configs WHERE version=?",
                              (config_version,)).fetchone()
        if cfg is None or date.fromisoformat(posted_on) < due + timedelta(days=cfg["grace_days"]):
            raise RuleError("Penalty is not yet due under the selected rule version.")
        if self.db.execute("SELECT 1 FROM allocations WHERE member_id=? AND due_saturday=?",
                           (member_id, due_saturday)).fetchone():
            raise RuleError("A paid installment cannot receive a missed-payment penalty.")
        prior = self.db.execute("""SELECT 1 FROM events WHERE member_id=? AND kind='penalty'
          AND json_extract(payload,'$.due_saturday')=?""", (member_id,due_saturday)).fetchone()
        if prior:
            raise RuleError("Penalty already posted for this due date.")
        with self.db:
            return self._event("penalty", member_id, cfg["penalty_birr"], posted_on,
                               actor, reason, config_version=config_version,
                               payload={"due_saturday": due_saturday})

    def reverse_event(self, event_id: str, actor: str, reason: str, occurred_on: str):
        original = self.db.execute("SELECT * FROM events WHERE event_id=?",
                                   (event_id,)).fetchone()
        if original is None or original["kind"] in ("reversal", "member_update"):
            raise RuleError("Original financial event cannot be reversed.")
        if not actor.strip() or not reason.strip():
            raise RuleError("Actor and reason are mandatory for corrections.")
        already = self.db.execute("SELECT 1 FROM events WHERE kind='reversal' AND ref_event_id=?",
                                  (event_id,)).fetchone()
        if already:
            raise RuleError("Event has already been reversed.")
        with self.db:
            reversal = self._event("reversal", original["member_id"], -original["amount"],
                                   occurred_on, actor, reason, ref_event_id=event_id,
                                   config_version=original["config_version"])
            if original["kind"] == "payment":
                self.db.execute("UPDATE allocations SET active=0 WHERE event_id=?", (event_id,))
                self.db.execute("UPDATE members SET last_paid_on=? WHERE member_id=?",
                                (self._last_valid_payment_date(original["member_id"]),
                                 original["member_id"]))
        return reversal

    def _last_valid_payment_date(self, member_id):
        row = self.db.execute("""SELECT MAX(occurred_on) AS d FROM events p
          WHERE p.member_id=? AND p.kind='payment'
          AND NOT EXISTS(SELECT 1 FROM events r WHERE r.kind='reversal' AND r.ref_event_id=p.event_id)""",
          (member_id,)).fetchone()
        return row["d"]

    def member_status(self, member_id: str, as_of: str):
        m = self.db.execute("SELECT * FROM members WHERE member_id=?", (member_id,)).fetchone()
        if m is None:
            raise RuleError("Member not found.")
        cfg = self.db.execute("""SELECT inactive_months FROM configs
          WHERE effective_from<=? ORDER BY effective_from DESC,version DESC LIMIT 1""",
          (as_of,)).fetchone()
        threshold = cfg["inactive_months"] if cfg else 6
        last = m["last_paid_on"] or m["joined_on"]
        cutoff = date.fromisoformat(as_of) - timedelta(days=30*threshold)
        return {**dict(m), "last_successful_payment": m["last_paid_on"],
                "suggested_status": "FROZEN" if date.fromisoformat(last) < cutoff else m["status"],
                "inactive_months_threshold": threshold}

    def reactivate(self, member_id: str, actor_role: str, actor: str,
                   settled_amount: int, occurred_on: str, reason: str):
        if actor_role != "SUPERVISOR":
            raise RuleError("Only the Supervisor can reactivate a frozen member.")
        status = self.member_status(member_id, occurred_on)
        if status["status"] == "ACTIVE" and status["suggested_status"] != "FROZEN":
            raise RuleError("Member is not frozen.")
        if not reason.strip() or not actor.strip():
            raise RuleError("Supervisor identity and reason are required.")
        # Outstanding penalties are calculated from posted, non-reversed penalty events.
        penalties = self.db.execute("""SELECT COALESCE(SUM(e.amount),0) AS total
          FROM events e WHERE e.member_id=? AND e.kind='penalty'
          AND NOT EXISTS(SELECT 1 FROM events r WHERE r.kind='reversal' AND r.ref_event_id=e.event_id)""",
          (member_id,)).fetchone()["total"]
        settlements = self.db.execute("""SELECT COALESCE(SUM(amount),0) AS total FROM events
          WHERE member_id=? AND kind='fine_settlement'""", (member_id,)).fetchone()["total"]
        outstanding = max(0, penalties - settlements)
        if settled_amount < outstanding:
            raise RuleError(f"Outstanding fines must be settled first: {outstanding} ETB.")
        with self.db:
            if settled_amount:
                self._event("fine_settlement", member_id, settled_amount, occurred_on,
                            actor, reason)
            self._event("reactivation", member_id, 0, occurred_on, actor, reason,
                        payload={"settled_fines": settled_amount})
            self.db.execute("UPDATE members SET status='ACTIVE' WHERE member_id=?",
                            (member_id,))
        return {"status": "ACTIVE", "fines_settled": settled_amount,
                "outstanding_fines": max(0, outstanding-settled_amount)}

    def operational_snapshot(self, as_of: str, lookback_months: int = 12):
        """Lean restore payload: roster, current config, recent events, all open future allocations."""
        if lookback_months != 12:
            raise RuleError("Collector operational restore uses a 12-month rolling window.")
        cutoff = date.fromisoformat(as_of) - timedelta(days=365)
        members = [self.member_status(r["member_id"], as_of)
                   for r in self.db.execute("SELECT member_id FROM members ORDER BY member_id")]
        events = [dict(r) for r in self.db.execute("""SELECT * FROM events
          WHERE occurred_on>=? AND occurred_on<=? ORDER BY occurred_on,event_id""",
          (cutoff.isoformat(), as_of))]
        allocations = [dict(r) for r in self.db.execute("""SELECT a.* FROM allocations a
          WHERE a.due_saturday>=? AND a.active=1 AND NOT EXISTS(
            SELECT 1 FROM events r WHERE r.kind='reversal' AND r.ref_event_id=a.event_id)
          ORDER BY a.due_saturday""", (as_of,))]
        cfg = self.db.execute("""SELECT * FROM configs WHERE effective_from<=?
          ORDER BY effective_from DESC,version DESC LIMIT 1""", (as_of,)).fetchone()
        return {"package_type": "VT_OPERATIONAL_RESTORE", "as_of": as_of,
                "lookback_months": 12, "members": members,
                "active_config": dict(cfg) if cfg else None,
                "recent_events": events, "outstanding_future_allocations": allocations,
                "record_counts": {"members": len(members), "events": len(events),
                                  "future_allocations": len(allocations)}}

    def supervisor_month_package(self, year: int, month: int):
        if not 1 <= month <= 12:
            raise RuleError("Month must be 1–12.")
        start = date(year, month, 1)
        end = date(year+1, 1, 1) - timedelta(days=1) if month == 12 else date(year,month+1,1)-timedelta(days=1)
        events = [dict(r) for r in self.db.execute("""SELECT * FROM events
          WHERE occurred_on BETWEEN ? AND ? ORDER BY occurred_on,event_id""",
          (start.isoformat(), end.isoformat()))]
        ids = sorted({e["member_id"] for e in events})
        members = [dict(r) for r in self.db.execute(
            "SELECT * FROM members WHERE member_id IN (%s)" % (",".join("?" for _ in ids) or "NULL"), ids)]
        return {"package_type": "VT_SUPERVISOR_MONTH", "year": year, "month": month,
                "start": start.isoformat(), "end": end.isoformat(),
                "events": events, "members": members,
                "event_count": len(events),
                "event_total": sum(e["amount"] for e in events if e["kind"] in ("payment","penalty","fine_settlement"))}

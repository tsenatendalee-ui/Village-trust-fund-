
"""Village Trust Fund - core rules engine (prototype).

Pure logic, no UI or network. Covers:
  - Ethiopian calendar conversion (EC dates shown to users)
  - Member IDs with a check digit
  - Versioned, supervisor-editable config (period, amount, grace, penalty)
  - Idempotent due / penalty postings
  - Append-only, hash-chained ledger (tamper-evident)
"""
import datetime as dt
import hashlib
import json
from dataclasses import dataclass

# ---------- Ethiopian calendar ----------
EC_EPOCH = 1724221        # JDN of Meskerem 1, year 1 E.C.
GREG_TO_JDN = 1721425     # JDN = date.toordinal() + GREG_TO_JDN
EC_MONTHS = ["Meskerem", "Tikimt", "Hidar", "Tahsas", "Tir", "Yekatit",
             "Megabit", "Miyazia", "Ginbot", "Sene", "Hamle", "Nehase", "Pagume"]


def _ec_year_start(y: int) -> int:
    """Days from the EC epoch to 1 Meskerem of year y."""
    return 365 * (y - 1) + y // 4


def greg_to_ec(d: dt.date) -> tuple[int, int, int]:
    off = d.toordinal() + GREG_TO_JDN - EC_EPOCH
    y = (4 * off + 3) // 1461 + 1
    while _ec_year_start(y + 1) <= off:
        y += 1
    while _ec_year_start(y) > off:
        y -= 1
    doy = off - _ec_year_start(y)
    m = min(doy // 30 + 1, 13)          # month 13 = Pagume (5 or 6 days)
    day = doy - 30 * (m - 1) + 1
    return y, m, day


def ec_to_greg(y: int, m: int, d: int) -> dt.date:
    off = _ec_year_start(y) + 30 * (m - 1) + d - 1
    return dt.date.fromordinal(EC_EPOCH + off - GREG_TO_JDN)


def ec_str(d: dt.date) -> str:
    y, m, day = greg_to_ec(d)
    return f"{day} {EC_MONTHS[m - 1]} {y} E.C."


# ---------- Member IDs ----------
def luhn_check(digits: str) -> int:
    total = 0
    for i, ch in enumerate(reversed(digits)):
        n = int(ch)
        if i % 2 == 0:
            n *= 2
            if n > 9:
                n -= 9
        total += n
    return (10 - total % 10) % 10


def make_member_id(seq: int) -> str:
    body = f"{seq:04d}"
    return f"VT-{body}-{luhn_check(body)}"


def valid_member_id(s: str) -> bool:
    parts = s.strip().upper().split("-")
    return (len(parts) == 3 and parts[0] == "VT" and parts[1].isdigit()
            and parts[2].isdigit() and luhn_check(parts[1]) == int(parts[2]))


# ---------- Config (supervisor-editable, versioned) ----------
@dataclass(frozen=True)
class Config:
    version: int
    amount: float = 100.0                  # birr per period
    period_days: int = 7                   # 7 = weekly, 14 = biweekly
    anchor: dt.date = dt.date(2026, 11, 14)  # first due date (a Saturday)
    grace_days: int = 7                    # days overdue before penalty starts
    penalty_mode: str = "fixed"            # "fixed" (birr) or "percent" (of amount)
    penalty_value: float = 10.0
    penalty_cap: float | None = None
    penalty_model: str = "per_period"      # "once" or "per_period" (repeats while unpaid)
    language: str = "am"                   # "am" or "en"


def penalty_for(cfg: Config) -> float:
    if cfg.penalty_mode == "percent":
        p = cfg.amount * cfg.penalty_value / 100
    else:
        p = cfg.penalty_value
    if cfg.penalty_cap is not None:
        p = min(p, cfg.penalty_cap)
    return round(p, 2)


def due_dates(cfg: Config, up_to: dt.date) -> list[dt.date]:
    out, d = [], cfg.anchor
    while d <= up_to:
        out.append(d)
        d += dt.timedelta(days=cfg.period_days)
    return out


# ---------- Hash-chained ledger ----------
@dataclass
class Entry:
    seq: int
    kind: str          # due | payment | penalty | reversal
    member: str
    ref: str
    amount: float
    at: str
    prev_hash: str
    hash: str


class Ledger:
    GENESIS = "GENESIS"

    def __init__(self):
        self.entries: list[Entry] = []
        self.keys: set = set()   # idempotency keys, e.g. ("payment", member, due)

    @staticmethod
    def _digest(seq, kind, member, ref, amount, at, prev) -> str:
        body = json.dumps([seq, kind, member, ref, amount, at, prev], sort_keys=True)
        return hashlib.sha256(body.encode()).hexdigest()

    def append(self, kind, member, ref, amount, at, key=None) -> Entry | None:
        if key is not None and key in self.keys:
            return None      # already posted: no duplicate payments or penalties
        prev = self.entries[-1].hash if self.entries else self.GENESIS
        seq = len(self.entries)
        h = self._digest(seq, kind, member, ref, amount, at, prev)
        e = Entry(seq, kind, member, ref, amount, at, prev, h)
        self.entries.append(e)
        if key is not None:
            self.keys.add(key)
        return e

    def verify(self) -> bool:
        prev = self.GENESIS
        for e in self.entries:
            if e.prev_hash != prev:
                return False
            if self._digest(e.seq, e.kind, e.member, e.ref, e.amount, e.at, prev) != e.hash:
                return False
            prev = e.hash
        return True


# ---------- Posting and balances ----------
def record_payment(ledger: Ledger, cfg: Config, member: str, due: dt.date, now: str):
    return ledger.append("payment", member, f"{due.isoformat()}|cfg{cfg.version}",
                         cfg.amount, now, key=("payment", member, due))


def accrue(ledger: Ledger, cfg: Config, member: str, today: dt.date, now: str) -> list[Entry]:
    """Post due and penalty entries up to today. Safe to run repeatedly."""
    posted = []
    for due in due_dates(cfg, today):
        e = ledger.append("due", member, f"{due.isoformat()}|cfg{cfg.version}",
                          cfg.amount, now, key=("due", member, due))
        if e:
            posted.append(e)
        if ("payment", member, due) in ledger.keys:
            continue
        for k in range(penalties_due(cfg, (today - due).days)):
            e = ledger.append("penalty", member, f"{due.isoformat()}|k{k}|cfg{cfg.version}",
                              penalty_for(cfg), now, key=("penalty", member, due, k))
            if e:
                posted.append(e)
    return posted


def penalties_due(cfg: Config, age_days: int) -> int:
    """How many penalties an unpaid due date has earned by now."""
    if age_days < cfg.grace_days:
        return 0
    if cfg.penalty_model == "once":
        return 1
    # per_period: one more penalty each period after the grace period, until paid
    return (age_days - cfg.grace_days) // cfg.period_days + 1


def balance(ledger: Ledger, member: str) -> float:
    owed = 0.0
    for e in ledger.entries:
        if e.member != member:
            continue
        if e.kind in ("due", "penalty"):
            owed += e.amount
        elif e.kind in ("payment", "reversal"):
            owed -= e.amount
    return round(owed, 2)


# ---------- Self-check and demo ----------
if __name__ == "__main__":
    # Calendar check: 12 Nov 2026 is 3 Hidar 2019 E.C.
    assert greg_to_ec(dt.date(2026, 11, 12)) == (2019, 3, 3)
    assert ec_to_greg(2019, 3, 3) == dt.date(2026, 11, 12)
    assert ec_str(dt.date(2026, 11, 14)) == "5 Hidar 2019 E.C."

    # ID check: a valid ID passes, a single-digit typo fails
    mid = make_member_id(1)
    assert valid_member_id(mid)
    assert not valid_member_id(mid[:-1] + str((int(mid[-1]) + 1) % 10))

    cfg = Config(version=1)
    ledger = Ledger()
    member = make_member_id(1)
    record_payment(ledger, cfg, member, dt.date(2026, 11, 14), "2026-11-14T10:00Z")
    accrue(ledger, cfg, member, dt.date(2026, 12, 1), "2026-12-01T06:00Z")
    accrue(ledger, cfg, member, dt.date(2026, 12, 1), "2026-12-01T06:00Z")  # rerun: no duplicates
    assert balance(ledger, member) == 210.0   # 3 dues (300) - 1 paid (100) + 1 penalty (10)
    assert ledger.verify()

    # Penalties keep building while unpaid (per_period model)
    accrue(ledger, cfg, member, dt.date(2026, 12, 12), "2026-12-12T06:00Z")
    accrue(ledger, cfg, member, dt.date(2026, 12, 12), "2026-12-12T06:00Z")  # rerun: no duplicates
    # dues: Nov14(paid), Nov21, Nov28, Dec5, Dec12 -> 500 owed, 100 paid
    # penalties: Nov21 age 21 -> 3, Nov28 age 14 -> 2, Dec5 age 7 -> 1 => 6 x 10 = 60
    assert balance(ledger, member) == 460.0
    assert ledger.verify()

    print("Member:", member, "| valid:", valid_member_id(member))
    print("Today:", ec_str(dt.date(2026, 12, 1)))
    for e in ledger.entries:
        print(f"  {e.seq:>2} {e.kind:<8} {e.ref:<28} {e.amount:>7.2f}")
    print("Balance (birr):", balance(ledger, member))
    print("Chain verified:", ledger.verify())

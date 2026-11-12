
"""Village Trust Fund - sync message format (shared by collector and supervisor apps).

Transport: Telegram bot messages (max ~4000 characters each, so large
payloads are split into parts). Every message is signed with HMAC-SHA256
using a per-fund key exchanged once at setup, and carries a sequence
number so replays and gaps are detected.

Message types
  report        collector -> supervisor : new ledger entries
  report_ack    supervisor -> collector : which entries were accepted
  update        supervisor -> collector : new config version
  update_ack    collector -> supervisor : config applied
"""
import hashlib
import hmac
import json

PROTOCOL_VERSION = 1
MAX_CHARS = 3500          # leave room under Telegram's 4096-character limit
CONFIG_FIELDS = ["version", "amount", "period_days", "anchor", "grace_days",
                 "penalty_mode", "penalty_value", "penalty_cap",
                 "penalty_model", "language"]


def _canonical(obj: dict) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def sign(key: bytes, envelope: dict) -> str:
    unsigned = {k: v for k, v in envelope.items() if k != "sig"}
    return hmac.new(key, _canonical(unsigned), hashlib.sha256).hexdigest()


def make_envelope(key: bytes, msg_type: str, sender: str, seq: int, sent: str, body: dict) -> dict:
    env = {"v": PROTOCOL_VERSION, "fund": "VT", "type": msg_type,
           "from": sender, "seq": seq, "sent": sent, "body": body}
    env["sig"] = sign(key, env)
    return env


def verify(key: bytes, env: dict) -> bool:
    if env.get("v") != PROTOCOL_VERSION or env.get("fund") != "VT":
        return False
    return hmac.compare_digest(env.get("sig", ""), sign(key, env))


def split_for_telegram(key: bytes, env: dict) -> list[str]:
    """Serialize an envelope; if too long, split the body into signed parts."""
    text = json.dumps(env, separators=(",", ":"), ensure_ascii=False)
    if len(text) <= MAX_CHARS:
        return [text]
    # Split the entry list into chunks that each fit, each part signed on its own.
    entries = env["body"].get("entries", [])
    parts, chunk, chunks = [], [], []
    for e in entries:
        trial = json.dumps({**env, "body": {**env["body"], "entries": chunk + [e]}},
                           separators=(",", ":"), ensure_ascii=False)
        if len(trial) > MAX_CHARS and chunk:
            chunks.append(chunk)
            chunk = []
        chunk.append(e)
    if chunk:
        chunks.append(chunk)
    for i, c in enumerate(chunks, 1):
        body = {**env["body"], "entries": c, "part": i, "parts": len(chunks)}
        part_env = make_envelope(key, env["type"], env["from"], env["seq"], env["sent"], body)
        parts.append(json.dumps(part_env, separators=(",", ":"), ensure_ascii=False))
    return parts


# ---------- Message builders ----------
def build_report(key, seq, sent, entries, cash_declared, first_entry_seq, last_entry_seq):
    # entry layout: [seq, kind, member, ref, amount, at, prev_hash, hash]
    total = round(sum(e[4] for e in entries if e[1] == "payment"), 2)
    body = {
        "entries": entries,              # each: [seq, kind, member, ref, amount, at, prev_hash, hash]
        "first_entry_seq": first_entry_seq,
        "last_entry_seq": last_entry_seq,
        "count": len(entries),
        "payments_total": total,         # birr collected in cash per the entries
        "cash_declared": cash_declared,  # birr the collector says is in hand
    }
    return split_for_telegram(key, make_envelope(key, "report", "collector", seq, sent, body))


def build_report_ack(key, seq, sent, report_seq, accepted_upto, rejected):
    body = {"report_seq": report_seq, "accepted_upto": accepted_upto, "rejected": rejected}
    return make_envelope(key, "report_ack", "supervisor", seq, sent, body)


def build_update(key, seq, sent, config: dict):
    body = {k: config[k] for k in CONFIG_FIELDS}   # anchor as ISO date string
    return make_envelope(key, "update", "supervisor", seq, sent, body)


def build_update_ack(key, seq, sent, config_version, applied_at):
    body = {"config_version": config_version, "applied_at": applied_at}
    return make_envelope(key, "update_ack", "collector", seq, sent, body)


# ---------- Receiver-side checks ----------
def check_sequence(last_seen: int, env: dict) -> str:
    """Return 'ok', 'replay' (seq already seen), or 'gap' (seq skipped)."""
    if env["seq"] <= last_seen:
        return "replay"
    if env["seq"] > last_seen + 1:
        return "gap"
    return "ok"


def check_entry_chain(prev_hash: str, entries: list) -> list[int]:
    """Return the sequence numbers of entries whose prev_hash link is broken."""
    broken = []
    for e in entries:
        if e[6] != prev_hash:
            broken.append(e[0])
        prev_hash = e[7]
    return broken


# ---------- Self-check ----------
if __name__ == "__main__":
    key = b"demo-key-set-at-setup"
    cfg = {"version": 2, "amount": 100.0, "period_days": 7, "anchor": "2026-11-14",
           "grace_days": 7, "penalty_mode": "fixed", "penalty_value": 10.0,
           "penalty_cap": None, "penalty_model": "per_period", "language": "am"}

    upd = build_update(key, 5, "2026-12-01T06:00Z", cfg)
    assert verify(key, upd)
    upd["body"]["amount"] = 999.0                       # tampering must fail
    assert not verify(key, upd)
    assert not verify(b"wrong-key", build_update(key, 5, "t", cfg))

    entries = [[0, "payment", "VT-0001-8", "2026-11-14|cfg1", 100.0, "2026-11-14T10:00Z", "GENESIS", "h0"],
               [1, "due", "VT-0001-8", "2026-11-21|cfg1", 100.0, "2026-11-21T06:00Z", "h0", "h1"]]
    rep = build_report(key, 7, "2026-11-22T18:00Z", entries, 100.0, 0, 1)
    assert len(rep) == 1 and verify(key, json.loads(rep[0]))
    assert check_sequence(6, json.loads(rep[0])) == "ok"
    assert check_sequence(7, json.loads(rep[0])) == "replay"
    assert check_sequence(4, json.loads(rep[0])) == "gap"
    assert check_entry_chain("GENESIS", entries) == []
    assert check_entry_chain("GENESIS", [entries[0], [1, "due", "x", "r", 1, "t", "WRONG", "h2"]]) == [1]

    big = [[i, "payment", "VT-0001-8", f"r{i}", 100.0, "2026-11-14T10:00Z", "p", "q"] for i in range(200)]
    parts = build_report(key, 8, "t", big, 20000.0, 0, 199)
    assert len(parts) > 1 and all(len(p) <= 4096 for p in parts)
    assert all(verify(key, json.loads(p)) for p in parts)

    print("Update message:", json.dumps(upd)[:160], "...")
    print("Report parts for 200 entries:", len(parts))
    print("All protocol checks passed.")

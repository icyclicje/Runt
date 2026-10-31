"""Your collection: every scan and every pack, in a local SQLite database."""
from __future__ import annotations

import csv
import io
import sqlite3
import threading
import time

SCHEMA = """
CREATE TABLE IF NOT EXISTS packs (
    id INTEGER PRIMARY KEY,
    set_id TEXT NOT NULL,
    started REAL NOT NULL,
    ended REAL,
    size INTEGER NOT NULL,
    cost REAL,
    complete INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS scans (
    id INTEGER PRIMARY KEY,
    ts REAL NOT NULL,
    card_key TEXT NOT NULL,
    set_id TEXT NOT NULL,
    number TEXT,
    name TEXT,
    rarity TEXT,
    variant TEXT NOT NULL DEFAULT 'normal',
    price REAL,
    pack_id INTEGER,
    dupe INTEGER NOT NULL DEFAULT 0,
    score REAL,
    source TEXT NOT NULL DEFAULT 'scan',
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS scans_card ON scans(card_key) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS scans_set ON scans(set_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS scans_pack ON scans(pack_id);
"""


def _row(r: sqlite3.Row | None) -> dict | None:
    return dict(r) if r is not None else None


class Store:
    def __init__(self, path: str):
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA synchronous=NORMAL')
        self.db.executescript(SCHEMA)
        self.lock = threading.RLock()

    def q(self, sql: str, args=()) -> list[dict]:
        with self.lock:
            return [dict(r) for r in self.db.execute(sql, args).fetchall()]

    def one(self, sql: str, args=()) -> dict | None:
        with self.lock:
            return _row(self.db.execute(sql, args).fetchone())

    def x(self, sql: str, args=()) -> int:
        with self.lock:
            cur = self.db.execute(sql, args)
            return cur.lastrowid

    # ------------------------------------------------------------ packs

    def open_pack(self, set_id: str, size: int, cost: float | None) -> int:
        return self.x('INSERT INTO packs(set_id, started, size, cost) VALUES (?,?,?,?)', (set_id, time.time(), size, cost))

    def current_pack(self) -> dict | None:
        return self.one('SELECT * FROM packs WHERE ended IS NULL ORDER BY id DESC LIMIT 1')

    def close_pack(self, pack_id: int, complete: bool) -> None:
        self.x('UPDATE packs SET ended=?, complete=? WHERE id=?', (time.time(), int(complete), pack_id))

    def reopen_pack(self, pack_id: int) -> None:
        self.x('UPDATE packs SET ended=NULL, complete=0 WHERE id=?', (pack_id,))

    def update_pack(self, pack_id: int, **fields) -> None:
        allowed = {k: v for k, v in fields.items() if k in ('set_id', 'size', 'cost')}
        if allowed:
            sets = ', '.join(f'{k}=?' for k in allowed)
            self.x(f'UPDATE packs SET {sets} WHERE id=?', (*allowed.values(), pack_id))

    def delete_pack(self, pack_id: int) -> None:
        with self.lock:
            self.db.execute('UPDATE scans SET deleted=1 WHERE pack_id=?', (pack_id,))
            self.db.execute('DELETE FROM packs WHERE id=?', (pack_id,))

    def pack(self, pack_id: int) -> dict | None:
        return self.one('SELECT * FROM packs WHERE id=?', (pack_id,))

    def packs(self, limit: int = 50, offset: int = 0, set_id: str | None = None) -> list[dict]:
        where, args = ('WHERE p.set_id=?', [set_id]) if set_id else ('', [])
        return self.q(f"""
            SELECT p.*, COUNT(s.id) AS cards, COALESCE(SUM(s.price), 0) AS value,
                   COALESCE(SUM(s.dupe), 0) AS dupes, MAX(s.price) AS best
            FROM packs p LEFT JOIN scans s ON s.pack_id = p.id AND s.deleted = 0
            {where} GROUP BY p.id ORDER BY p.id DESC LIMIT ? OFFSET ?""", (*args, limit, offset))

    def pack_totals(self, set_id: str | None = None, since: float | None = None) -> dict:
        cond, args = ['1=1'], []
        if set_id:
            cond.append('p.set_id=?')
            args.append(set_id)
        if since:
            cond.append('p.started>=?')
            args.append(since)
        return self.one(f"""
            SELECT COUNT(*) AS packs, COALESCE(SUM(cost), 0) AS cost, COALESCE(SUM(value), 0) AS value,
                   COALESCE(MAX(value), 0) AS best_pack, COALESCE(SUM(cards), 0) AS cards
            FROM (SELECT p.id, p.cost, COUNT(s.id) AS cards, COALESCE(SUM(s.price), 0) AS value
                  FROM packs p LEFT JOIN scans s ON s.pack_id = p.id AND s.deleted = 0
                  WHERE {' AND '.join(cond)} GROUP BY p.id HAVING cards > 0)""", args)

    # ------------------------------------------------------------ scans

    def add_scan(self, card, variant: str, price: float, pack_id: int | None, dupe: bool, score: float | None,
                 source: str = 'scan') -> int:
        return self.x("""INSERT INTO scans(ts, card_key, set_id, number, name, rarity, variant, price, pack_id, dupe, score, source)
                         VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                      (time.time(), card.key, card.set_id, card.number, card.name, card.rarity, variant, price,
                       pack_id, int(dupe), score, source))

    def scan(self, scan_id: int) -> dict | None:
        return self.one('SELECT * FROM scans WHERE id=?', (scan_id,))

    def delete_scan(self, scan_id: int) -> None:
        self.x('UPDATE scans SET deleted=1 WHERE id=?', (scan_id,))

    def restore_scan(self, scan_id: int) -> None:
        self.x('UPDATE scans SET deleted=0 WHERE id=?', (scan_id,))

    def update_scan(self, scan_id: int, **fields) -> None:
        allowed = {k: v for k, v in fields.items()
                   if k in ('card_key', 'set_id', 'number', 'name', 'rarity', 'variant', 'price', 'dupe', 'source')}
        if allowed:
            sets = ', '.join(f'{k}=?' for k in allowed)
            self.x(f'UPDATE scans SET {sets} WHERE id=?', (*allowed.values(), scan_id))

    def pack_scans(self, pack_id: int) -> list[dict]:
        return self.q('SELECT * FROM scans WHERE pack_id=? AND deleted=0 ORDER BY id', (pack_id,))

    def recent(self, limit: int = 30) -> list[dict]:
        return self.q('SELECT * FROM scans WHERE deleted=0 ORDER BY id DESC LIMIT ?', (limit,))

    def last_scan(self) -> dict | None:
        return self.one('SELECT * FROM scans WHERE deleted=0 ORDER BY id DESC LIMIT 1')

    def copies(self, card_key: str, exclude_id: int | None = None) -> int:
        r = self.one('SELECT COUNT(*) AS n FROM scans WHERE card_key=? AND deleted=0 AND id IS NOT ?', (card_key, exclude_id))
        return r['n'] if r else 0

    def owned(self, set_id: str | None = None, include_dupes: bool = True) -> dict[str, dict]:
        """card_key -> {n, variants, first, value}"""
        cond = ['deleted=0']
        args = []
        if set_id:
            cond.append('set_id=?')
            args.append(set_id)
        if not include_dupes:
            cond.append('dupe=0')
        rows = self.q(f"""SELECT card_key, COUNT(*) AS n, GROUP_CONCAT(DISTINCT variant) AS variants,
                                 MIN(ts) AS first, SUM(price) AS value
                          FROM scans WHERE {' AND '.join(cond)} GROUP BY card_key""", args)
        return {r['card_key']: r for r in rows}

    def owned_by_set(self) -> dict[str, int]:
        rows = self.q('SELECT set_id, COUNT(DISTINCT card_key) AS n FROM scans WHERE deleted=0 GROUP BY set_id')
        return {r['set_id']: r['n'] for r in rows}

    def totals(self, since: float | None = None) -> dict:
        cond, args = 'deleted=0', ()
        if since:
            cond, args = 'deleted=0 AND ts>=?', (since,)
        return self.one(f"""SELECT COUNT(*) AS scans, COUNT(DISTINCT card_key) AS unique_cards,
                                   COALESCE(SUM(price), 0) AS value, COALESCE(SUM(dupe), 0) AS dupes,
                                   COALESCE(MAX(price), 0) AS best
                            FROM scans WHERE {cond}""", args)

    def export_csv(self) -> str:
        rows = self.q("""SELECT s.id, datetime(s.ts, 'unixepoch', 'localtime') AS scanned, s.set_id, s.number, s.name,
                                s.rarity, s.variant, s.price, s.dupe, s.pack_id, s.source
                         FROM scans s WHERE s.deleted=0 ORDER BY s.id""")
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(['scan_id', 'scanned', 'set', 'number', 'name', 'rarity', 'variant', 'price_usd', 'duplicate', 'pack', 'source'])
        for r in rows:
            w.writerow([r['id'], r['scanned'], r['set_id'], r['number'], r['name'], r['rarity'], r['variant'],
                        f"{r['price']:.2f}" if r['price'] is not None else '', 'yes' if r['dupe'] else '', r['pack_id'] or '', r['source']])
        return buf.getvalue()

    def reset_all(self) -> None:
        with self.lock:
            self.db.execute('DELETE FROM scans')
            self.db.execute('DELETE FROM packs')

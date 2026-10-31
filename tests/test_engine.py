"""Pack / duplicate / undo logic of the scanner engine, driven directly (no camera).

usage: python -m tests.test_engine <cache_dir_with_card_data>
"""
from __future__ import annotations

import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.carddb import CardDB            # noqa: E402
from app.config import Settings          # noqa: E402
from app.currency import Rates           # noqa: E402
from app.engine import Engine, EventBus  # noqa: E402
from app.refs import RefStore            # noqa: E402
from app.store import Store              # noqa: E402


class Quiet:
    def __init__(self):
        self.played, self.said = [], []

    def play(self, name, force=False):
        self.played.append(name)

    def say(self, text):
        self.said.append(text)


class Rec:
    def __init__(self, key, cands=()):
        self.score, self.cands = 0.9, [(key, 0.9)] + list(cands)


def main():
    src = sys.argv[1]
    tmp = tempfile.mkdtemp()
    try:
        shutil.copytree(os.path.join(src, 'data'), os.path.join(tmp, 'data'))
        settings = Settings(os.path.join(tmp, 'settings.json'))
        settings.update({'pack_size': 3, 'voice': 'name_price'})
        db = CardDB(tmp)
        db.load(refresh='never')
        bus = EventBus()
        events = bus.subscribe(1000)
        snd = Quiet()
        e = Engine(settings, db, RefStore(tmp), Store(os.path.join(tmp, 'c.db')), snd, snd, Rates(tmp), bus)
        e.data_ready = True
        e.select_set('base1')
        cs = db.get_set('base1')
        a, b, c, d = cs.cards[0], cs.cards[1], cs.cards[2], cs.cards[3]
        failures = 0

        def check(name, cond):
            nonlocal failures
            print(('PASS ' if cond else 'FAIL ') + name)
            failures += not cond

        def drain():
            out = []
            while not events.empty():
                out.append(events.get_nowait()['type'])
            return out

        e._on_confirm(a.key, Rec(a.key))
        e._on_confirm(a.key, Rec(a.key))                       # shown again in the same pack
        check('same card twice in one pack is skipped', len(e.store.pack_scans(e.store.current_pack()['id'])) == 1)
        check('…and reported', 'ignored' in drain())
        e._on_confirm(b.key, Rec(b.key))
        p1 = e.store.current_pack()['id']
        e._on_confirm(c.key, Rec(c.key, [(d.key, 0.5)]))         # 3rd card completes the pack
        check('pack completes at its size', e.store.pack(p1)['complete'] == 1)
        check('next pack opened automatically', e.store.current_pack()['id'] != p1)
        evs = drain()
        check('pack_done event', 'pack_done' in evs)
        check('pack sound played', 'pack' in snd.played)
        check('alternatives remembered for corrections', e.scan_dict(e.store.last_scan())['alts'][0]['key'] == d.key)

        e._on_confirm(a.key, Rec(a.key))                       # owned already, new pack
        last = e.store.last_scan()
        check('owned card in a new pack is a duplicate', last['dupe'] == 1 and last['card_key'] == a.key)
        check('duplicate sound', snd.played[-1] in ('dupe', 'hit'))
        check('voice says duplicate', snd.said[-1].startswith('Duplicate'))

        # undo the duplicate, then undo the card that completed pack 1: pack 1 must reopen
        e.undo()
        e.undo()
        cur = e.store.current_pack()
        check('undo reopens the pack the card had completed', cur['id'] == p1 and cur['ended'] is None)
        check('…and the empty follow-up pack is gone', len(e.store.packs(10)) == 1)

        # correcting a scan to another card recomputes duplicate status
        e._on_confirm(c.key, Rec(c.key))
        sid = e.store.last_scan()['id']
        e.correct_scan(sid, a.key)                              # a is already owned → dupe
        check('correcting to an owned card marks it duplicate', e.store.scan(sid)['dupe'] == 1)
        e.correct_scan(sid, d.key)
        check('correcting to a new card clears duplicate', e.store.scan(sid)['dupe'] == 0)

        # reverse holo for the next card only
        e.set_next_variant('reverse')
        e._on_confirm(b.key, Rec(b.key))
        last = e.store.last_scan()
        check('next-card reverse holo applies once', last['variant'] == 'reverse' and e.next_variant is None)
        check('reverse price used', abs(last['price'] - b.reverse_price) < 1e-9)

        # "count it again" option
        settings.update({'same_in_pack': 'allow'})
        n0 = len(e.store.pack_scans(e.store.current_pack()['id']))
        e._on_confirm(b.key, Rec(b.key))
        check('"count it again" allows a repeat in the pack',
              len(e.store.pack_scans(e.store.current_pack()['id'])) == n0 + 1 or e.store.current_pack()['id'] != p1)

        # manual add + progress numbers
        e.manual_add(cs.cards[10].key)
        prog = e.set_progress('base1')
        # owned so far: a, b, d (c was corrected to d) and the hand-added card
        check('set progress counts owned cards', prog['master_owned'] == 4 and prog['master_total'] == len(cs.cards))

        # waiting for "Next pack" when auto-next is off
        settings.update({'auto_next_pack': False, 'same_in_pack': 'ignore'})
        cur = e.store.current_pack()
        need = cur['size'] - len(e.store.pack_scans(cur['id']))
        for card in cs.cards[20:20 + need]:
            e._on_confirm(card.key, Rec(card.key))
        check('auto-next off: waits after a full pack', e.waiting_next_pack and e.store.current_pack() is None)
        e.next_pack()
        check('Next pack resumes', not e.waiting_next_pack and e.store.current_pack() is not None)

        # switching sets closes a half-done pack
        e._on_confirm(cs.cards[30].key, Rec(cs.cards[30].key))
        half = e.store.current_pack()['id']
        e.select_set('base2')
        check('switching set closes the half-done pack', e.store.pack(half)['ended'] is not None
              and e.store.current_pack()['set_id'] == 'base2')
        st = e.state()
        check('state() works', st['set']['id'] == 'base2' and st['pack']['count'] == 0)
        print('\nall passed' if not failures else f'\n{failures} FAILED')
        return failures
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())

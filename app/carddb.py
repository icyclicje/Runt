"""Card database: sets, cards and prices from the MivimooseRips site.

The site serves its data as JavaScript files that call PackData.<fn>(<json>).
We download them, pull the JSON arguments out, and keep a copy on disk so the
scanner starts instantly and keeps working offline.
"""
from __future__ import annotations

import gzip
import json
import logging
import os
import re
import threading
import time
import urllib.request

log = logging.getLogger(__name__)

DEFAULT_SITE = 'https://mivimooserips-production-e1c1.up.railway.app'
UA = 'MivimooseScanner/1.0 (+desktop)'
DATA_FILES = ('data/bundle.js', 'data/pack-art.js')

IMG_BASE = 'https://images.pokemontcg.io/'
TCGDEX_BASE = 'https://assets.tcgdex.net/'
TCGP_BASE = 'https://tcgplayer-cdn.tcgplayer.com/product/'

# Typical price for a rarity, used when nothing in the set has a market price (first match wins).
_DEFAULT_PRICE = [
    (r'^common$', 0.08), (r'^uncommon$', 0.12), (r'^rare$', 0.3), (r'^(rare holo|holo rare)$', 1.0), (r'promo', 1.0),
    (r'special illustration|special art|character super|super rare|\bsar\b', 30), (r'hyper|rainbow|secret|gold|\bur\b', 18),
    (r'illustration|character|\bar\b|\bchr\b', 5), (r'shiny|shining|star|legend|amazing|radiant', 6),
    (r'ultra|full art|\bsr\b', 6), (r'double|triple|ex|gx|vmax|vstar|\bv\b|\brr\b', 1.5),
]
_DEFAULT_PRICE = [(re.compile(p, re.I), v) for p, v in _DEFAULT_PRICE]
FIRST_EDITION = ('base2', 'base3', 'base5', 'gym1', 'gym2', 'neo1', 'neo2', 'neo3', 'neo4')
_SUFFIX_RE = re.compile(r'(^|[\s\-&(])(ex|gx|v|vmax|vstar|v-union|lv\.x)(?=$|[\s,&)\-])', re.I)
_ENUM_RE = re.compile(r'^[A-Z]+(_[A-Z]+)+$')
_HIT_RE = re.compile(r'illustration|ultra|hyper|secret|special|character|shiny|triple|gold|rainbow|full art|radiant|amazing|\bsar\b|\bsir\b|\bur\b', re.I)


def default_price(rarity: str) -> float:
    for rx, v in _DEFAULT_PRICE:
        if rx.search(rarity or ''):
            return v
    return 2.0


def tidy_name(n: str) -> str:
    return _SUFFIX_RE.sub(lambda m: m.group(1) + m.group(2).upper(), str(n or ''))


def norm_rarity(r):
    if r and _ENUM_RE.match(r):
        return ' '.join(w.capitalize() for w in r.lower().split('_'))
    return r or ''


def natural_key(s: str):
    return [(0, int(t), '') if t.isdigit() else (1, 0, t.lower()) for t in re.findall(r'\d+|\D+', str(s))]


def image_urls(img: str) -> tuple[str, str]:
    """(small, large) image URLs for the site's image codes."""
    if img.startswith('sx:'):
        s, num = img[3:].split('/', 1)
        return (f'https://images.scrydex.com/pokemon/{s}-{num}/small', f'https://images.scrydex.com/pokemon/{s}-{num}/large')
    if img.startswith('tcgp:'):
        i = img[5:]
        return (f'{TCGP_BASE}{i}_200w.jpg', f'{TCGP_BASE}{i}_in_1000x1000.jpg')
    if img.startswith('tcgdex:'):
        p = TCGDEX_BASE + img[7:]
        return (p + '/low.webp', p + '/high.webp')
    return (IMG_BASE + img + '.png', IMG_BASE + img + '_hires.png')


class Card:
    __slots__ = ('key', 'set_id', 'set_name', 'lang', 'number', 'name', 'name_local', 'rarity', 'kind', 'subtypes',
                 'price', 'price_known', 'reverse_price', 'first_ed_price', 'tcg_id', 'img', 'img_large', 'no_image',
                 'first_edition', 'main_set', 'order')

    def price_for(self, variant: str) -> float:
        if variant == 'reverse':
            return self.reverse_price
        return self.price

    @property
    def is_hit(self) -> bool:
        return bool(_HIT_RE.search(self.rarity or ''))

    def to_dict(self) -> dict:
        return {
            'key': self.key, 'setId': self.set_id, 'setName': self.set_name, 'lang': self.lang,
            'number': self.number, 'name': self.name, 'nameLocal': self.name_local, 'rarity': self.rarity,
            'kind': self.kind, 'price': self.price, 'priceKnown': self.price_known,
            'reversePrice': self.reverse_price, 'img': self.img, 'imgLarge': self.img_large,
            'noImage': self.no_image, 'mainSet': self.main_set, 'hit': self.is_hit, 'order': self.order,
        }


class CardSet:
    def __init__(self, raw: dict):
        self.id = raw['id']
        self.name = raw.get('name') or self.id
        self.name_local = raw.get('nameLocal') if raw.get('nameLocal') != self.name else None
        self.series = raw.get('series') or ''
        self.release = (raw.get('releaseDate') or '').replace('/', '-')
        self.printed = int(raw.get('printed') or 0)
        self.logo = raw.get('logo')
        self.symbol = raw.get('symbol')
        self.lang = raw.get('lang') or 'en'
        self.source = raw.get('source')
        self.pack_size_data = raw.get('packSize')
        self.estimated = bool(raw.get('estimated'))
        self.first_edition = False
        self.base_id = None
        self.sealed_price = None
        self.cards: list[Card] = []
        if re.match(r'^(pop|mcd)\d', self.id):
            self.series = 'Rare & Promo Packs'

    @property
    def year(self) -> int:
        try:
            return int(self.release[:4])
        except ValueError:
            return 2020

    @property
    def pack_size(self) -> int:
        """Cards in one booster pack (scannable cards, not code cards)."""
        if self.pack_size_data:
            return int(self.pack_size_data)
        if self.lang != 'en':
            return 5
        y = self.year
        if y <= 2002:
            return 11
        if y <= 2007:
            return 9
        return 10

    @property
    def main_count(self) -> int:
        return sum(1 for c in self.cards if c.main_set)

    def summary(self) -> dict:
        return {
            'id': self.id, 'name': self.name, 'nameLocal': self.name_local, 'series': self.series,
            'release': self.release, 'printed': self.printed, 'logo': self.logo, 'symbol': self.symbol,
            'lang': self.lang, 'total': len(self.cards), 'mainCount': self.main_count, 'packSize': self.pack_size,
            'sealedPrice': self.sealed_price, 'estimated': self.estimated, 'firstEdition': self.first_edition,
            'scannable': sum(1 for c in self.cards if not c.no_image),
        }


def _calls(text: str):
    """Yield (function name, decoded JSON argument) for every PackData.fn(...) call in a data file."""
    dec = json.JSONDecoder()
    for m in re.finditer(r'PackData\.(\w+)\(', text):
        try:
            obj, _ = dec.raw_decode(text, m.end())
        except ValueError:
            continue
        yield m.group(1), obj


def _round2(x: float) -> float:
    return round(x * 100) / 100


class CardDB:
    def __init__(self, cache_dir: str, site_url: str = DEFAULT_SITE):
        self.cache_dir = os.path.join(cache_dir, 'data')
        os.makedirs(self.cache_dir, exist_ok=True)
        self.site_url = site_url.rstrip('/')
        self.sets: dict[str, CardSet] = {}
        self.order: list[str] = []
        self.by_key: dict[str, Card] = {}
        self.updated = ''
        self.loaded_at = 0.0
        self.lock = threading.RLock()
        self.status = 'not loaded'

    # ------------------------------------------------------------ download

    def _path(self, name: str) -> str:
        return os.path.join(self.cache_dir, os.path.basename(name))

    def _fetch(self, rel: str, timeout: float = 60) -> bool:
        """Download one data file into the cache. True when the cache changed."""
        path = self._path(rel)
        meta_path = path + '.etag'
        req = urllib.request.Request(f'{self.site_url}/{rel}', headers={'User-Agent': UA, 'Accept-Encoding': 'gzip'})
        if os.path.exists(path) and os.path.exists(meta_path):
            with open(meta_path, encoding='utf-8') as f:
                etag = f.read().strip()
            if etag:
                req.add_header('If-None-Match', etag)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
                if r.headers.get('Content-Encoding') == 'gzip':
                    body = gzip.decompress(body)
                etag = r.headers.get('ETag') or ''
        except urllib.error.HTTPError as e:
            if e.code == 304:
                os.utime(path)
                return False
            raise
        tmp = path + '.tmp'
        with open(tmp, 'wb') as f:
            f.write(body)
        os.replace(tmp, path)
        with open(meta_path, 'w', encoding='utf-8') as f:
            f.write(etag)
        return True

    def cache_age(self) -> float:
        p = self._path(DATA_FILES[0])
        return time.time() - os.path.getmtime(p) if os.path.exists(p) else float('inf')

    def load(self, refresh: str = 'auto', max_age_h: float = 12, progress=None) -> None:
        """refresh: 'auto' (download if the cache is old), 'force', or 'never'."""
        have_cache = all(os.path.exists(self._path(f)) for f in DATA_FILES)
        need = refresh == 'force' or not have_cache or (refresh == 'auto' and self.cache_age() > max_age_h * 3600)
        if have_cache and not self.sets:
            self._parse()                            # start from the cache straight away
        if need and refresh != 'never':
            try:
                self.status = 'downloading card data'
                if progress:
                    progress('Downloading card database…')
                changed = False
                for f in DATA_FILES:
                    changed |= self._fetch(f)
                if changed or not self.sets:
                    self._parse()
            except Exception as e:                   # offline: keep whatever the cache had
                log.warning('card data download failed: %s', e)
                if not self.sets:
                    self.status = f'offline and no cached data ({e})'
                    raise
        self._apply_live_prices()
        self.status = f'{len(self.sets)} sets, {len(self.by_key)} cards'

    # ------------------------------------------------------------ parse

    def _parse(self) -> None:
        t0 = time.time()
        with open(self._path('data/bundle.js'), encoding='utf-8') as f:
            bundle = f.read()
        pack_art = {}
        try:
            with open(self._path('data/pack-art.js'), encoding='utf-8') as f:
                for fn, arg in _calls(f.read()):
                    if fn == 'setPackArt':
                        pack_art = arg
        except OSError:
            pass
        sets: dict[str, CardSet] = {}
        for fn, raw in _calls(bundle):
            if fn != 'addSet' or not isinstance(raw, dict):
                continue
            s = self._build_set(raw)
            sets[s.id] = s
            self.updated = max(self.updated, raw.get('updated') or '')
        for base_id in FIRST_EDITION:
            if base_id in sets:
                fe = self._first_edition(sets[base_id])
                sets[fe.id] = fe
        for s in sets.values():
            s.sealed_price = _sealed_price(pack_art.get(s.id))
        order = sorted(sets, key=lambda i: (sets[i].lang != 'en', sets[i].release, sets[i].name), reverse=False)
        by_key = {c.key: c for s in sets.values() for c in s.cards}
        with self.lock:
            self.sets, self.order, self.by_key = sets, order, by_key
            self.loaded_at = time.time()
        log.info('parsed %d sets / %d cards in %.1fs', len(sets), len(by_key), time.time() - t0)

    def _build_set(self, raw: dict) -> CardSet:
        s = CardSet(raw)
        rows = [c for c in raw.get('cards', []) if isinstance(c, list)]
        intl = s.lang != 'en'
        by_rarity: dict[str, list] = {}
        for c in rows:
            if len(c) > 3 and c[3] is not None:
                by_rarity.setdefault(norm_rarity(c[2]), []).append(c[3])
        median = {r: sorted(v)[len(v) // 2] for r, v in by_rarity.items()}
        seen: dict[str, int] = {}
        for i, c in enumerate(rows):
            c = (c + [None] * 12)[:12]
            number, name, raw_rarity, price, rev, img, kind, subtypes, fe_price, name_local, tcg_id, _ = c
            card = Card()
            card.set_id, card.set_name, card.lang = s.id, s.name, s.lang
            card.number = str(number or '')
            card.name = tidy_name(name)
            card.name_local = name_local if name_local and name_local != name else None
            card.rarity = norm_rarity(raw_rarity)
            card.kind = kind or ''
            card.subtypes = subtypes.split('|') if subtypes else []
            base = price if price is not None else median.get(card.rarity, default_price(card.rarity))
            card.price = float(base)
            card.price_known = price is not None
            card.reverse_price = float(rev) if (not intl and rev is not None) else max(0.1, _round2(base * 1.4))
            card.first_ed_price = fe_price
            card.tcg_id = tcg_id
            card.first_edition = False
            card.no_image = not img
            card.img, card.img_large = image_urls(img) if img else ('', '')
            n = re.match(r'^(\d+)$', card.number)
            card.main_set = bool(n) and (not s.printed or int(n.group(1)) <= s.printed)
            card.order = i
            base_key = f'{s.id}|{card.number}|{card.name}'
            seen[base_key] = seen.get(base_key, 0) + 1
            card.key = base_key if seen[base_key] == 1 else f'{base_key}#{seen[base_key]}'
            s.cards.append(card)
        if not s.estimated:
            s.estimated = sum(1 for c in s.cards if c.price_known) < len(s.cards) * 0.25
        return s

    def _first_edition(self, base: CardSet) -> CardSet:
        s = CardSet({'id': base.id + '-1st', 'name': base.name + ' 1st Edition', 'series': 'Rare & Promo Packs',
                     'releaseDate': base.release, 'printed': base.printed, 'logo': base.logo, 'symbol': base.symbol})
        s.first_edition, s.base_id = True, base.id
        for bc in base.cards:
            c = Card()
            for f in Card.__slots__:
                setattr(c, f, getattr(bc, f))
            c.key = bc.key.replace(base.id + '|', s.id + '|', 1)
            c.set_id, c.set_name, c.first_edition = s.id, s.name, True
            c.price = float(bc.first_ed_price) if bc.first_ed_price is not None else _round2(bc.price * 2.5)
            c.price_known = bc.first_ed_price is not None
            s.cards.append(c)
        return s

    def _apply_live_prices(self) -> None:
        """Newer prices from the site's server, when it has them (keyed by TCGplayer id)."""
        try:
            req = urllib.request.Request(f'{self.site_url}/api/prices', headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=8) as r:
                prices = (json.loads(r.read()) or {}).get('prices') or {}
        except Exception:
            return
        n = 0
        with self.lock:
            for c in self.by_key.values():
                if c.tcg_id is not None and not c.first_edition:
                    v = prices.get(str(c.tcg_id))
                    if v is not None:
                        c.price, c.price_known = float(v), True
                        n += 1
        log.info('applied %d live prices', n)

    # ------------------------------------------------------------ queries

    def get_set(self, set_id: str) -> CardSet | None:
        return self.sets.get(set_id)

    def card(self, key: str) -> Card | None:
        return self.by_key.get(key)

    def set_list(self) -> list[dict]:
        return [self.sets[i].summary() for i in self.order]

    def search(self, q: str, set_id: str | None = None, limit: int = 40) -> list[Card]:
        q = (q or '').strip().lower()
        if not q:
            return []
        terms = q.split()
        pool = self.sets[set_id].cards if set_id and set_id in self.sets else self.by_key.values()
        out = []
        for c in pool:
            hay = f'{c.name} {c.name_local or ""} {c.number} {c.set_name} {c.rarity}'.lower()
            if all(t in hay for t in terms):
                out.append(c)
                if len(out) >= limit * 4:
                    break
        out.sort(key=lambda c: (not c.name.lower().startswith(terms[0]), -c.price))
        return out[:limit]


def _sealed_price(a) -> float | None:
    """Median of the per-pack prices of single packs, bundles and boxes (ignoring outliers)."""
    if not a:
        return None
    s = a.get('s') or {}
    vals = [v for v in s.values() if isinstance(v, (int, float)) and v > 0]
    if s.get('pack'):
        vals = [v for v in vals if s['pack'] / 2 <= v <= s['pack'] * 2]
    if not vals:
        return a.get('m')
    vals.sort()
    mid = len(vals) // 2
    return vals[mid] if len(vals) % 2 else _round2((vals[mid - 1] + vals[mid]) / 2)

"""Exchange rates for showing prices in your currency (card prices are in US dollars)."""
from __future__ import annotations

import json
import logging
import os
import threading
import time
import urllib.request

log = logging.getLogger(__name__)

SYMBOLS = {'USD': '$', 'CAD': 'CA$', 'EUR': '€', 'GBP': '£', 'AUD': 'A$', 'JPY': '¥', 'MXN': 'MX$', 'NZD': 'NZ$'}
SPOKEN = {'USD': 'dollars', 'CAD': 'Canadian dollars', 'EUR': 'euros', 'GBP': 'pounds', 'AUD': 'Australian dollars',
          'JPY': 'yen', 'MXN': 'pesos', 'NZD': 'New Zealand dollars'}
# Used until the first download works (approximate).
FALLBACK = {'USD': 1.0, 'CAD': 1.38, 'EUR': 0.92, 'GBP': 0.78, 'AUD': 1.52, 'JPY': 150.0, 'MXN': 18.5, 'NZD': 1.66}


class Rates:
    def __init__(self, cache_dir: str):
        self.path = os.path.join(cache_dir, 'rates.json')
        self.rates = dict(FALLBACK)
        self.updated = 0.0
        try:
            with open(self.path, encoding='utf-8') as f:
                d = json.load(f)
            self.rates.update({k: float(v) for k, v in d['rates'].items() if k in FALLBACK})
            self.updated = float(d['updated'])
        except (OSError, ValueError, KeyError):
            pass
        if time.time() - self.updated > 24 * 3600:
            threading.Thread(target=self.refresh, name='rates', daemon=True).start()

    def refresh(self):
        try:
            req = urllib.request.Request('https://open.er-api.com/v6/latest/USD', headers={'User-Agent': 'MivimooseScanner'})
            with urllib.request.urlopen(req, timeout=10) as r:
                d = json.loads(r.read())
            got = {k: float(v) for k, v in d.get('rates', {}).items() if k in FALLBACK}
            if got:
                self.rates.update(got)
                self.updated = time.time()
                with open(self.path, 'w', encoding='utf-8') as f:
                    json.dump({'rates': self.rates, 'updated': self.updated}, f)
        except Exception as e:
            log.info('exchange rates not updated: %s', e)

    def convert(self, usd: float, cur: str) -> float:
        return usd * self.rates.get(cur, 1.0)

    def fmt(self, usd: float | None, cur: str) -> str:
        if usd is None:
            return '—'
        v = self.convert(usd, cur)
        sym = SYMBOLS.get(cur, '$')
        if cur == 'JPY':
            return f'{sym}{v:,.0f}'
        return f'{sym}{v:,.2f}'

    def spoken(self, usd: float, cur: str) -> str:
        v = self.convert(usd, cur)
        unit = SPOKEN.get(cur, 'dollars')
        if v >= 100 or cur == 'JPY':
            return f'{v:,.0f} {unit}'
        if v < 1:
            return f'{round(v * 100)} cents' if cur not in ('GBP',) else f'{round(v * 100)} pence'
        return f'{v:.2f} {unit}'

"""Spoken announcements ("Charizard ex, 42 dollars") with Windows' built-in voice."""
from __future__ import annotations

import logging
import queue
import threading

log = logging.getLogger(__name__)

_ASYNC, _PURGE = 1, 2


class Speaker:
    def __init__(self, settings):
        self.settings = settings
        self._q: queue.Queue = queue.Queue()
        self.available = True
        threading.Thread(target=self._loop, name='speech', daemon=True).start()

    def say(self, text: str):
        if self.settings['voice'] != 'off' and self.available:
            self._q.put(text)

    def _loop(self):
        try:
            import comtypes
            import comtypes.client
            comtypes.CoInitialize()
            voice = comtypes.client.CreateObject('SAPI.SpVoice')
        except Exception as e:
            log.warning('speech unavailable: %s', e)
            self.available = False
            return
        while True:
            text = self._q.get()
            while not self._q.empty():               # only the newest announcement matters
                text = self._q.get()
            try:
                voice.Rate = int(self.settings['voice_rate'])
                voice.Speak(text, _ASYNC | _PURGE)
            except Exception as e:
                log.debug('speech failed: %s', e)

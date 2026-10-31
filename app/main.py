"""MivimooseRips Scanner: entry point.

Starts the scanner, the local server for the desktop window, and the SteamVR
overlay, then opens the app window (WebView2). Closing the window quits.

  MivimooseScanner.exe                 normal start
  MivimooseScanner.exe --browser       open the dashboard in your web browser instead of a window
  MivimooseScanner.exe --camera file:C:\\clip.mp4   scan a video file (testing)
"""
from __future__ import annotations

import argparse
import ctypes
import logging
import logging.handlers
import os
import sys
import threading
import webbrowser


def _setup_logging(dd: str):
    logdir = os.path.join(dd, 'logs')
    os.makedirs(logdir, exist_ok=True)
    handlers = [logging.handlers.RotatingFileHandler(os.path.join(logdir, 'scanner.log'), maxBytes=2_000_000,
                                                     backupCount=2, encoding='utf-8')]
    if sys.stderr is not None and not getattr(sys, 'frozen', False):
        handlers.append(logging.StreamHandler())
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s: %(message)s', handlers=handlers)


def _single_instance(dd: str) -> bool:
    """True if we are the only copy running. Otherwise opens the running copy's window in the browser."""
    if os.name != 'nt':
        return True
    ctypes.windll.kernel32.CreateMutexW(None, False, 'Local\\MivimooseScannerInstance')
    if ctypes.windll.kernel32.GetLastError() == 183:          # ERROR_ALREADY_EXISTS
        try:
            with open(os.path.join(dd, 'url.txt'), encoding='utf-8') as f:
                webbrowser.open(f.read().strip())
        except OSError:
            pass
        return False
    return True


def main(argv=None):
    from .config import APP_NAME, Settings, data_dir, resource_dir

    ap = argparse.ArgumentParser(prog='MivimooseScanner')
    ap.add_argument('--browser', action='store_true', help='open in the web browser instead of an app window')
    ap.add_argument('--headless', action='store_true', help='no window at all (the dashboard is still served)')
    ap.add_argument('--camera', help='camera override for this run, e.g. webcam:1 or file:C:\\video.mp4')
    ap.add_argument('--port', type=int)
    ap.add_argument('--data-dir', help='keep settings, collection and caches here')
    args = ap.parse_args(argv)

    dd = args.data_dir or data_dir()
    os.makedirs(dd, exist_ok=True)
    _setup_logging(dd)
    log = logging.getLogger('main')
    if not _single_instance(dd):
        return 0

    from . import hud
    from .carddb import CardDB
    from .currency import Rates
    from .engine import Engine, EventBus
    from .refs import RefStore
    from .server import App, Server
    from .sounds import SoundPlayer
    from .speech import Speaker
    from .store import Store
    from .vr import VRManager

    settings = Settings(os.path.join(dd, 'settings.json'))
    if args.camera:
        settings.override('camera', args.camera)
    if args.port:
        settings.override('port', args.port)

    bus = EventBus()
    db = CardDB(dd, settings['site_url'])
    refs = RefStore(dd)
    store = Store(os.path.join(dd, 'collection.db'))
    rates = Rates(dd)
    sounds = SoundPlayer(dd, settings)
    speaker = Speaker(settings)
    engine = Engine(settings, db, refs, store, sounds, speaker, rates, bus)

    icon_path = os.path.join(dd, 'icon.png')
    if not os.path.exists(icon_path):
        hud.icon_image(256).save(icon_path)
    images = hud.ImageCache()
    vr = VRManager(engine, settings, bus, images, lambda usd: rates.fmt(usd, settings['currency']), icon_path)
    engine.vr = vr

    quit_event = threading.Event()
    window_ref = {}

    def quit_app():
        quit_event.set()
        w = window_ref.get('w')
        if w is not None:
            try:
                w.destroy()
            except Exception:
                pass

    web_dir = os.path.join(resource_dir(), 'web')
    server = Server(App(engine, settings, db, store, refs, rates, bus, web_dir, quit_app), settings['port'], settings['lan'])
    with open(os.path.join(dd, 'url.txt'), 'w', encoding='utf-8') as f:
        f.write(server.url)
    log.info('%s running at %s (data in %s)', APP_NAME, server.url, dd)
    engine.start()

    shown = False
    if args.headless:
        try:
            quit_event.wait()
        except KeyboardInterrupt:
            pass
        shown = True
    elif not args.browser:
        try:
            import webview
            w = webview.create_window(APP_NAME, server.url, width=1440, height=920, min_size=(960, 640),
                                      background_color='#070a17', text_select=True)
            window_ref['w'] = w
            webview.start(storage_path=os.path.join(dd, 'webview'), private_mode=False)
            shown = True
        except Exception as e:
            log.warning('app window unavailable (%s); opening the browser instead', e)
    if not shown:
        webbrowser.open(server.url)
        try:
            quit_event.wait()
        except KeyboardInterrupt:
            pass

    log.info('shutting down')
    vr.stop()
    engine.stop()
    server.stop()
    return 0


if __name__ == '__main__':
    sys.exit(main())

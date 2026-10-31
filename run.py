"""Launcher (this is what PyInstaller turns into MivimooseScanner.exe)."""
import sys

from app.main import main

if __name__ == '__main__':
    sys.exit(main())

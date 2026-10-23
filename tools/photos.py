#!/usr/bin/env python3
"""Sends your photos to the TV for Free My TV's screensaver and home screen (My Photos).

    python3 tools/photos.py 192.168.1.50 beach.jpg hills.jpg Holidays/
    python3 tools/photos.py beach.jpg              # the TV adb is already connected to

The photos go, in the order given (a folder's in name order), to the app's own folder on the TV
(Android/data/io.freemytv/files/photos: adb can write there, and the app needs no permission). The next time the
home screen, the screensaver or Free My TV's settings open, the app imports each one (upright, cropped to 16:9, at
the screensaver's size, without its GPS and other tags) and deletes what was sent. Up to 100 photos in all; remove
them on the TV: Home Settings > My Photos > OK. JPEG, PNG and WebP; HEIC on Android 9 and newer.
"""
import os
import subprocess
import sys

INBOX = '/sdcard/Android/data/io.freemytv/files/photos'
KINDS = ('.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif')


def adb(*args):
    return subprocess.run(['adb', *args], capture_output=True, text=True)


def photos(paths):
    out = []
    for p in paths:
        if os.path.isdir(p):
            for root, dirs, files in os.walk(p):
                dirs[:] = sorted(d for d in dirs if not d.startswith('.'))
                out += [os.path.join(root, f) for f in sorted(files)
                        if not f.startswith('.') and f.lower().endswith(KINDS)]
        elif os.path.isfile(p) and p.lower().endswith(KINDS):
            out.append(p)
        else:
            print(f'Skipped {p}: not a photo or a folder.')
    return out


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0 if args else 2
    if not os.path.exists(args[0]) and args[0][0].isdigit():
        ip = args.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = adb('connect', target)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            return 1
    found = photos(args)
    if not found:
        print('No photos to send.')
        return 1
    if adb('shell', 'mkdir', '-p', INBOX).returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return 1
    for i, path in enumerate(found):
        # Numbered so the app imports them in this order.
        name = f'{i:03d}-' + os.path.basename(path).replace(' ', '_')
        r = adb('push', path, f'{INBOX}/{name}')
        if r.returncode != 0:
            print(f'Could not send {path}: {(r.stdout + r.stderr).strip()}')
            return 1
        print(f'Sent {path}')
    print(f'{len(found)} photo(s) on the TV. Open the home screen or Free My TV\'s settings to import them.')
    return 0


if __name__ == '__main__':
    sys.exit(main())

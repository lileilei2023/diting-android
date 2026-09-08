#!/usr/bin/env python3
"""Tap the first on-screen element whose text contains the argument (exact match wins).

    python3 scripts/adb_tap.py "同步全部录音"

Uses `uiautomator dump`, so it fails while the screen is animating — sleep a second
after navigating. Device serial is the test phone; override with DITING_ADB_SERIAL.
"""
import os
import re,sys,subprocess
SERIAL=os.environ.get('DITING_ADB_SERIAL','R5CWA1YQ6MD')
q=sys.argv[1]
x=subprocess.run(['adb','-s',SERIAL,'exec-out','uiautomator','dump','/dev/stdout'],capture_output=True,text=True).stdout
hits=[m.groups() for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)]
hits=sorted([h for h in hits if q in h[0]], key=lambda h: h[0]!=q)
for t,a,b,c,d in hits:
    if True:
        cx,cy=(int(a)+int(c))//2,(int(b)+int(d))//2
        print(t,cx,cy); subprocess.run(['adb','-s',SERIAL,'shell','input','tap',str(cx),str(cy)]); sys.exit(0)
print('NOTFOUND',q); sys.exit(1)

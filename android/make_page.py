#!/usr/bin/env python3
# Writes assets/index.html: the web game (../web/page.html + engine.js) with fonts bundled for offline play.
# Run from android/; build-apk.sh and the Gradle build both call it.
import os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
import re
page = open('../web/page.html').read().replace('/*ENGINE*/', open('../web/engine.js').read())
page = re.sub(r'<link rel="(preconnect|stylesheet)"[^>]*>\n', '', page)
fonts = """<style>
@font-face { font-family: "Bricolage Grotesque"; src: url(fonts/bricolage.woff2) format("woff2"); font-weight: 200 800; }
@font-face { font-family: "Figtree"; src: url(fonts/figtree.woff2) format("woff2"); font-weight: 300 900; }
:root { padding-top: env(safe-area-inset-top, 0px); padding-bottom: env(safe-area-inset-bottom, 0px); }
body { margin: 0; -webkit-tap-highlight-color: transparent; }
[hidden] { display: none !important; }
</style>
"""
head = '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">\n'
title_end = page.index('</title>') + len('</title>')
out = head + page[:title_end] + '\n' + fonts + '</head>\n<body>\n' + page[title_end:] + '\n</body>\n</html>\n'
open('assets/index.html', 'w').write(out)

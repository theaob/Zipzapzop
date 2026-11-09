#!/bin/sh
# Inline engine.js into page.html -> index.html (the published single file), stamping the build date
# and commit for the version line in Settings. Also writes the GitHub Pages copies under docs/:
# docs/play/ (the game as a full page, for shared links opened without the app) and docs/daily/ (the
# landing page shared Daily links point to, from link.html).
cd "$(dirname "$0")" && BUILD="$(date -u +%Y-%m-%d)$(git rev-parse --short HEAD 2>/dev/null | sed 's/^/ · /')" python3 -c "
import os
s=open('page.html').read(); e=open('engine.js').read()
game=s.replace('/*ENGINE*/', e).replace('__WEB_BUILD__', os.environ['BUILD'])
open('index.html','w').write(game)
os.makedirs('../docs/play', exist_ok=True); os.makedirs('../docs/daily', exist_ok=True)
end=game.index('</title>')+len('</title>')
head='<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n'
style='<style>body { margin: 0; } [hidden] { display: none !important; }</style>\n'
open('../docs/play/index.html','w').write(head+game[:end]+'\n'+style+'</head>\n<body>\n'+game[end:]+'\n</body>\n</html>\n')
open('../docs/daily/index.html','w').write(open('link.html').read().replace('/*ENGINE*/', e))"

#!/bin/sh
# Inline engine.js into page.html -> index.html (the published single file).
cd "$(dirname "$0")" && python3 -c "
s=open('page.html').read(); e=open('engine.js').read()
open('index.html','w').write(s.replace('/*ENGINE*/', e))"

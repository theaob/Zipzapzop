#!/bin/sh
# Inline engine.js into page.html -> index.html (the published single file), stamping the build date
# and commit for the version line in Settings.
cd "$(dirname "$0")" && BUILD="$(date -u +%Y-%m-%d)$(git rev-parse --short HEAD 2>/dev/null | sed 's/^/ · /')" python3 -c "
import os
s=open('page.html').read(); e=open('engine.js').read()
open('index.html','w').write(s.replace('/*ENGINE*/', e).replace('__WEB_BUILD__', os.environ['BUILD']))"

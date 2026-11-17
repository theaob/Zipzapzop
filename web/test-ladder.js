// Checks that all Ladder levels have exactly one solution. Run: node web/test-ladder.js
// Reads the world sizes and worldSpec() from page.html, so the levels checked are the ones the game builds.
const fs = require('fs'), path = require('path');
const E = require('./engine.js');
const page = fs.readFileSync(path.join(__dirname, 'page.html'), 'utf8');
const sizes = [...page.slice(page.indexOf('const WORLDS = ['), page.indexOf('const WORLD_LEVELS')).matchAll(/\bn: (\d+)/g)].map((m) => +m[1]);
const WORLD_LEVELS = +page.match(/const WORLD_LEVELS = (\d+)/)[1];
const WORLDS = sizes.map((n) => ({ n }));
const worldSpec = eval('(' + page.match(/function worldSpec\(w, l\) \{[\s\S]*?\n  \}/)[0] + ')');
let bad = 0;
for (let w = 0; w < WORLDS.length; w++)
  for (let l = 1; l <= WORLD_LEVELS; l++) {
    const spec = worldSpec(w, l);
    const puz = E.generateFromKey(spec.n, 'zap/world/' + (w + 1) + '/' + l, spec.opts);
    const res = E.solve(puz, puz.solution, 5000000);
    if (!puz.unique || res.count !== 1 || !E.isSolved(puz, puz.solution)) {
      bad++; console.log(`World ${w + 1} level ${l}: ${res.exhausted ? 'unchecked' : res.count + ' solutions'}`);
    }
  }
const total = WORLDS.length * WORLD_LEVELS;
console.log(bad ? `${bad} of ${total} Ladder levels are not unique` : `All ${total} Ladder levels have one solution`);
process.exit(bad ? 1 : 0);

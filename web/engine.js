// Zap puzzle engine: generator + solver. Pure functions, no DOM.
const ZapEngine = (() => {
  function mulberry32(seed) {
    return function () {
      seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  function hashString(s) {
    let h = 2166136261;
    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return h >>> 0;
  }

  const wallKey = (a, b) => (a < b ? a + '-' + b : b + '-' + a);

  function neighbors(n, i) {
    const r = Math.floor(i / n), c = i % n, out = [];
    if (r > 0) out.push(i - n);
    if (r < n - 1) out.push(i + n);
    if (c > 0) out.push(i - 1);
    if (c < n - 1) out.push(i + 1);
    return out;
  }

  // Random Hamiltonian path via backbite moves on a serpentine start.
  function randomPath(n, rand) {
    let path = [];
    for (let r = 0; r < n; r++)
      for (let k = 0; k < n; k++) path.push(r * n + (r % 2 ? n - 1 - k : k));
    const steps = n * n * 60;
    for (let s = 0; s < steps; s++) {
      if (rand() < 0.5) path.reverse();
      const end = path[0];
      const opts = neighbors(n, end).filter((x) => x !== path[1]);
      const nb = opts[Math.floor(rand() * opts.length)];
      const j = path.indexOf(nb);
      path = path.slice(0, j).reverse().concat(path.slice(j));
    }
    return path;
  }

  // Counts solutions up to 2 within a node budget. Returns
  // { count, alt, exhausted } where alt is a solution differing from `known`.
  function solve(puz, known, budget = 300000) {
    const { n, nums, walls } = puz;
    const total = n * n;
    const numAt = new Int16Array(total);
    let K = 0, start = -1, endCell = -1;
    for (const [cell, k] of Object.entries(nums)) {
      numAt[+cell] = k;
      if (k === 1) start = +cell;
      if (k > K) { K = k; endCell = +cell; }
    }
    const adj = [];
    for (let i = 0; i < total; i++)
      adj.push(neighbors(n, i).filter((j) => !walls.has(wallKey(i, j))));
    const visited = new Uint8Array(total);
    const path = [start];
    visited[start] = 1;
    let nodes = 0, count = 0, alt = null, exhausted = false;
    const knownStr = known ? known.join(',') : null;

    function okCell(u, head) {
      if (visited[u]) return true;
      let d = 0;
      for (const v of adj[u]) if (!visited[v] || v === head) d++;
      return u === endCell ? d >= 1 : d >= 2;
    }

    function dfs(head, next) {
      if (count >= 2 || exhausted) return;
      if (++nodes > budget) { exhausted = true; return; }
      if (path.length === total) {
        if (head === endCell) {
          count++;
          if (!alt && path.join(',') !== knownStr) alt = path.slice();
        }
        return;
      }
      for (const nb of adj[head]) {
        if (visited[nb]) continue;
        const k = numAt[nb];
        if (k && k !== next) continue;
        if (nb === endCell && path.length + 1 !== total) continue;
        visited[nb] = 1; path.push(nb);
        let ok = true;
        for (const u of adj[head]) if (!okCell(u, nb)) { ok = false; break; }
        if (ok) for (const u of adj[nb]) if (!okCell(u, nb)) { ok = false; break; }
        if (ok) dfs(nb, k ? next + 1 : next);
        path.pop(); visited[nb] = 0;
        if (count >= 2 || exhausted) return;
      }
    }
    dfs(start, 2);
    return { count, alt, exhausted };
  }

  function generate(n, seed) {
    const rand = mulberry32(seed);
    const sol = randomPath(n, rand);
    const L = sol.length;
    const pos = new Int16Array(L);
    sol.forEach((c, i) => (pos[c] = i));

    // Checkpoints: both ends plus a spread of interior points.
    const want = Math.max(4, Math.round(n * 0.9 + rand() * 3));
    const idxs = new Set([0, L - 1]);
    for (let k = 1; k < want - 1; k++) {
      const base = (k * (L - 1)) / (want - 1);
      const jitter = (rand() - 0.5) * ((L - 1) / (want - 1)) * 0.6;
      idxs.add(Math.min(L - 2, Math.max(1, Math.round(base + jitter))));
    }
    const nums = {};
    [...idxs].sort((a, b) => a - b).forEach((pi, k) => (nums[sol[pi]] = k + 1));

    // Candidate walls: adjacent pairs the solution never steps between.
    const candidates = [];
    for (let i = 0; i < L; i++)
      for (const j of neighbors(n, i))
        if (i < j && Math.abs(pos[i] - pos[j]) !== 1) candidates.push(wallKey(i, j));
    const walls = new Set();
    const startWalls = Math.floor(n / 2 + rand() * 3);
    for (let w = 0; w < startWalls && candidates.length; w++)
      walls.add(candidates.splice(Math.floor(rand() * candidates.length), 1)[0]);

    const puz = { n, nums, walls, solution: sol, unique: false };
    // Tighten until the solution is unique (or we run out of tries).
    for (let iter = 0; iter < 60; iter++) {
      const res = solve(puz, sol);
      if (res.exhausted) break;
      if (res.count <= 1) { puz.unique = true; break; }
      const alt = res.alt;
      let d = 0;
      while (alt[d] === sol[d]) d++;
      if (rand() < 0.55 || nums[sol[d]]) {
        walls.add(wallKey(alt[d - 1], alt[d]));
      } else {
        // Add a checkpoint at the solution cell where they diverge, renumbering.
        const order = Object.keys(nums).map(Number).concat([sol[d]])
          .sort((a, b) => pos[a] - pos[b]);
        for (const k in nums) delete nums[k];
        order.forEach((c, i) => (nums[c] = i + 1));
      }
    }
    return puz;
  }

  // Validate a player path against the rules (not against the stored solution).
  function isSolved(puz, path) {
    const { n, nums, walls } = puz;
    if (path.length !== n * n) return false;
    const seen = new Set(path);
    if (seen.size !== n * n) return false;
    let next = 1;
    const K = Object.keys(nums).length;
    for (let i = 0; i < path.length; i++) {
      if (i > 0) {
        const a = path[i - 1], b = path[i];
        if (!neighbors(n, a).includes(b) || walls.has(wallKey(a, b))) return false;
      }
      const k = nums[path[i]];
      if (k) { if (k !== next) return false; next++; }
    }
    return next === K + 1 && nums[path[path.length - 1]] === K;
  }

  return { generate, solve, isSolved, neighbors, wallKey, hashString, mulberry32 };
})();
if (typeof module !== 'undefined') module.exports = ZapEngine;

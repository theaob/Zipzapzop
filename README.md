# Zap

A path-drawing puzzle: draw one line through every cell, pass the numbers in order, finish on the highest one, and don't cross the walls. Inspired by LinkedIn's Zip.

- **Play in a browser:** open [`web/index.html`](web/index.html).
- **Android:** see [`android/README.md`](android/README.md).

## Layout
| Path | What it is |
| --- | --- |
| `web/engine.js` | Puzzle generator, solver and rule checker (no DOM). Every puzzle is checked to have exactly one solution. |
| `web/page.html` | The game UI, with an `/*ENGINE*/` placeholder. |
| `web/build.sh` | Inlines the engine into `web/index.html`, the single-file playable page. Run it after editing either source. |
| `android/` | WebView wrapper that bundles the same game for offline play. |

Daily puzzles are seeded from the date and grid size, so everyone gets the same puzzle on the same day. Practice puzzles are random.

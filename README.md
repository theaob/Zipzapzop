# ZipZapZop

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
| `docs/privacy.html` | Privacy policy, served by GitHub Pages from `docs/` on `main`. |

Each day has three daily challenges (Easy 5×5, Medium 6×6, Hard 7×7), the same for everyone on that date. Ladder has five themed worlds of 20 levels each (Meadow 4×4, Ocean 5×5, Desert 6×6, Aurora 7×7, Volcano 8×8); within a world each level starts with fewer numbers and more walls, and finishing a world unlocks the next. Finished worlds can be replayed. Learn replays the four tutorial rounds.

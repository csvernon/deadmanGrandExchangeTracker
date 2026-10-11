# Location Data

`src/main/resources/com/deadman/world/landmarks.json` contains named world-map anchors extracted directly from the locally installed Old School RuneScape game cache. It does not contain a third-party plugin's curated spawn locations or aliases.

- Export date: 2026-10-11.
- Export tooling: RuneLite cache API 1.13.1, invoked only during development.
- Source: a copy of the `main_file_cache*` files in the local `jagexcache/oldschool/LIVE` directory.
- Source `main_file_cache.dat2` SHA-256: `4be4d9d4c232495ca7744c1e114728ceb796cee37e3751ab5cd3bd9814e24633`.
- Output: 1,054 unique named labels with world coordinates, plane, and label size.

The independently written exporter is in `tools/landmark-export`. It emits JSON through its logger. Run it against a COPY of the cache, not against a running client's cache:

```powershell
.\gradlew.bat -p tools/landmark-export run --args="C:/path/to/copied/cache"
```

The `LANDMARK_DATA` log record contains the JSON array. Its `label`, `x`, `y`, `plane`, and `labelSize` values are read from map elements and area definitions. Equal records are deduplicated and sorted by label; there is no hand-curated spawn-coordinate table.

The runtime resolver offsets automatic anchors by one tile east and north. Custom overrides are not shifted. Label size 2 is treated as a broad region. Ambiguous labels are deliberately not mapped. Map-label coordinates and directional offsets are approximate, not a guarantee of a chest's or monster's exact tile.

Game names and geographic facts originate in Jagex's game data. No game artwork, cache binaries, or RuneLite cache implementation is distributed in the plugin. This provenance record is not a legal clearance or a claim of ownership of game content.

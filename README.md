# Deadman

A RuneLite plugin for permanent Deadman world 345, maintained by Riskin.

## Grand Exchange

- Looks up recent Deadman buys and sells, trade history, and price charts in the sidebar.
- Adds recent Deadman buy/sell prices to the Grand Exchange item text.
- Records world-345 Grand Exchange transactions in the existing shared trade feed.
- Stores its local trade cache in RuneLite's managed plugin directory.
- Keeps separate caches for each history limit so clients using different settings do not remove each other's history.

The saved `Trade history limit` dropdown offers 1 week, 2 weeks, 1 month, 2 months, or 3 months, defaulting to 3 months. Weeks and calendar months are calculated in UTC. Changing the setting immediately removes trades outside the selected window and restarts background history loading to recover any missing trades. Older cached trades are removed during background loading, and history downloads stop at the selected boundary. Page requests are spaced at least five seconds apart and respect longer server rate-limit delays. During synchronization, cache saves and sidebar refresh notifications are batched every thirty seconds, with a final save when synchronization completes or the plugin is disabled. Item lookups use an index, and item names are resolved in small client-thread batches.

## Breaches And Deadman's Chests

The plugin tracks breach and Deadman's Chest announcements received as game messages or broadcasts on world 345. Player chat cannot create markers.

- The sidebar lists active locations, Single/Multi combat when announced, location precision, and estimated time remaining.
- Clickable world-map pins focus the map on a location. Off-screen pins snap to the map edge and point toward the location.
- The minimap shows nearby location icons and directional arrows for distant surface locations. Underground locations with known surface entrances use those entrances when you are on the surface. Arrows are hidden in instances, child world views, and underground areas without established connectivity.
- Chest and breach map markers can be enabled separately. Minimap arrows have their own setting. Hiding markers does not remove locations from the sidebar.
- The existing weekend breach countdown remains available.

The plugin bundles 1,054 named world-map anchors extracted from the game cache. These are map labels, not a database of exact chest spawn tiles. Automatic markers are offset one tile east and north from a label's anchor. Directional announcements offset the relevant landmark; region labels identify only a broad area. Unknown or ambiguous names remain visible without a guessed marker.

`Location overrides` in the plugin settings accepts one entry per line:

```text
Place name | x,y,plane
Underground place | x,y,plane | entranceX,entranceY,entrancePlane
```

Overrides take priority and use the entered coordinates without offsets. Settings are saved by RuneLite. An underground location only produces a surface minimap arrow when an entrance override is provided. Exact spawn locations should be supplied from independently observed in-game coordinates. See [location data provenance](docs/LOCATION-DATA.md).

A chest estimate expires ten minutes after its announcement, or when a new chest is announced. Breach locations expire after forty-five minutes (fifteen minutes of spawning plus up to thirty minutes before remaining monsters despawn). These are estimates, not confirmation that a chest is still unclaimed or that monsters remain. Duplicate announcements do not reset their timers.

Locations clear on logout, reconnect, world hopping, and plugin shutdown. Ordinary scene loading preserves active locations. No events from before login are fetched, and locations are not saved across restarts. Location tracking does not send broadcasts to a server, download location updates, or change the game's hint arrow.

## Development

Requires JDK 11 or newer.

```powershell
.\gradlew.bat clean build
.\gradlew.bat run
```

For a local developer-mode client, `gradlew.bat deploy` copies the plugin JAR to RuneLite's sideloaded-plugins directory. Restart that client to load the updated JAR. A normal Plugin Hub installation receives updates through the Hub after review.

The landmark extraction tool is development-only and is not included in the plugin or its runtime dependencies.

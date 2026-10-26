# DiPlay next release notes

Changes through DiPlay 0.2.20 are documented in [0.2.20 release notes](RELEASE-NOTES-0.2.20.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

No unreleased changes are recorded yet. Add future unreleased changes here.

## Lincoln/Ford cluster navigation

Guidance to the instrument cluster now uses the channel the build that works on the MEK-MX8Q / CX483
car uses, instead of the invented `com.baidu.car.navi.*` one the cluster never listened to:

- the navigation state announcement (`ACTION_PHONE_NAV_ACTIVE_OPEN`/`CLOSE`, the
  `KEY_TYPE` 10101/10100 status broadcast with `MODULE_TYPE=NAVI`, and the app-wide
  `com.baidu.naviauto.start_navigation`/`stop_navigation`) that opens and closes the cluster's
  navigation view;
- turn-by-turn on `com.baidu.naviauto.action.send` with every alias the stock app fills, the
  navigation app's own turn index, and a 21-character street name, sent with
  `FLAG_INCLUDE_STOPPED_PACKAGES`;
- the Baidu NaviSDK HUD socket (`127.0.0.1`, ports 9394/54321-54330/9293, `BaiduNavi_LOVE_U`), which
  the head unit's HUD side can read instead of the broadcasts.

The card is still held and repeated every second while the route runs, and a stated end still has to
hold for a second before guidance is closed, so the earlier "the card vanishes after half a minute
and comes back" behaviour does not return. Music lyrics are unchanged. In-car acceptance still
needs to be measured; `scripts/lincoln-cluster-probe.sh inject` sends one maneuver on this protocol
so the cluster's own reaction can be watched.

In-car measurement on the CX483 car then showed the cluster opening on an empty "0 m" card for a few
seconds at each route start. Two things caused it, both now handled:

- the head unit's navigation client (the Baidu NaviSDK remote that the cluster app reads on a ~1 s
  cycle) only connected after the navigation view had already been opened, so the first card was
  drawn from its empty state. The HUD socket is now offered for the whole CarPlay session and only
  released when the session drops or the switch goes off, so the client is connected and
  authenticated before guidance starts. A route end only tells the clients the route finished; the
  socket stays up for the next route.
- the iPhone publishes a 0 m placeholder for the first frames of a route, before it has a distance
  for the first leg (measured at ~30 ms on the car). That placeholder is now held back for up to
  three seconds instead of opening the cluster's navigation view with it, and is drawn anyway if it
  is all the iPhone has, so a maneuver that genuinely starts at 0 m still appears.

Measured on the car with the head unit's own logs (`BNRemote onManeuver`, `NavPresenterImpl
sendNaviInfo`, `CarVendorInstrumentClusterManager SignalID:0x22/0x20`): before, the cluster's first
card came 1.8 s after the navigation opened, with `distance=0` and `StreetName:null`; now the first
card it renders already carries the turn and the street name. One trade-off comes with holding the
ports: while a CarPlay session is connected with the switch on, the stock Baidu navigation app
cannot bind them for its own HUD, so a native route taken during a CarPlay session would not reach
the cluster. Turning the navigation switch off releases the ports again.

## Cluster junction progress bar

The cluster's own navigation adapter (`Instrumentcluster.apk`, versionName 1.0, package
`com.baidu.xiaoduos.instrumentcluster`) draws the junction progress bar itself, from the earlier
`com.baidu.car.navi.INFO` channel that this car's adapter reads:

- it takes the maneuver name, `distance` and `nextRoadName`, plus two doubles it uses **only as the
  junction's identity**. When the pair changes it records the distance it was just handed as that
  junction's starting distance and then computes `distance / start * 256` for the vehicle service's
  `BargraphSteps`. The pair is never displayed and never reaches the vehicle service.
- iAP2 gives us maneuver ids, not junction coordinates, so the pair is a synthetic token per
  maneuver; the car's moving GPS position must never be substituted, because every fix would start a
  new junction. The token is sent on both the INFO channel and the HUD socket so the two inputs
  cannot restart each other.
- the adapter's division is unclamped and never acknowledged. `LincolnManeuverTracker` therefore
  keeps every distance sent under one identity non-increasing, so a receiver that missed the earlier
  packets — or restarted mid-leg — still computes 0..256 from its own first packet. A real increase
  (a detour, an unannounced reroute) is only accepted after confirmation (1-4 m noise never counts;
  5-49 m needs three fresh frames over 5 s; 50 m and more needs two fresh frames over 1 s) and then
  starts a new identity. Only frames carrying a new phone distance count; snapshot ticks, lyrics and
  our own repeats do not.

Supporting changes in the same area:

- `BydHudRouteState` now exposes the maneuver id, a distance revision that only counts accepted
  distance frames, a local progress generation and a "recalculating" state. iAP2 state 3 (Loading)
  and 5 (Rerouting) start one new generation per calculation and hold the last packet until guidance
  resumes with a fresh distance, so a reused junction id cannot inherit the old baseline.
- `CarPlayGlance` adds `latestPublished()`, and the cluster output reads it under its own lock
  instead of trusting the snapshot handed to its callback. A listener is notified outside the
  glance's lock, so a delayed consumer could act on state a newer frame had already replaced and
  re-send an older, larger distance: the car's logs showed the cluster recomputing progress from it.
- `scripts/lincoln-cluster-probe.sh progress` drives one identity with a falling distance
  (350 -> 250 -> 150 -> 50 m) and prints the `BargraphSteps` the adapter hands on, so the adapter can
  be checked without CarPlay.

In-car acceptance for the bar is still needed: watch the cluster screen while a route shortens a leg,
and check that `BargraphSteps` falls as the distance does instead of staying at 1.

## Cluster text is cut by characters, not code units

- `LincolnClusterProtocol.sanitizeText` counts its budget in UTF-16 units - 32 for the music card, 21
  for the street name - and truncates on a character boundary. A cut used to be able to land between
  the two halves of a supplementary character or inside an emoji or combining sequence, which hands
  the cluster a symbol it cannot draw.
- The boundary search starts from `BreakIterator` and then retreats over whole sequences, because
  older Android character iterators split newer emoji. A sequence that crosses the budget is dropped
  whole: surrogate pairs, combining marks, variation selectors, keycaps and joined families, skin-tone
  modifiers, regional-indicator flag pairs and tag characters. No ellipsis is appended, as it would
  take space the cluster does not have.

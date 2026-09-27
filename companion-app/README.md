# Navio Companion App

Android companion app that bridges food-delivery apps (Swiggy, Zomato) to the Navio ESP32 display unit.

## How it works

```
[Swiggy/Zomato order notification]
        |
        v
[NotificationListenerService] -- extracts drop/pickup address
        |
        v
[RideController]
  - geocodes address (Nominatim)
  - routes from current GPS (OSRM, free OpenStreetMap stack)
  - GPS-snaps rider to route, computes distance/turn/speed
        |
        v  BLE (Nordic UART, 17-byte chunked JSON)
[ESP32 + ST7789 display] -- mini-map polyline + rider position + turn info
```

The BLE protocol is byte-for-byte compatible with `index.html` (the web debug controller),
which sends the same `t:"R"` route packets and `t:"U"` position/turn packets.

## BLE wire protocol

- Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Characteristic UUID: `6e400002-b5a3-f393-e0a9-e50e24dcca9e` (write/writeWithoutResponse)
- JSON is sanitized to printable ASCII, split into chunks of up to 17 chars,
  each prefixed with 2-digit hex sequence number + `1`/`0` end flag,
  8 ms between writes.

Packets:
- `{"t":"R","pts":[[lat,lon],...]}` — full route, max 48 downsampled points
- `{"t":"U",lat,lon,hdg,spd,dist,turn,road}` — position, heading (deg),
  speed (km/h), distance to next turn (m), turn code
  (0 straight, 1 left, 2 right, 3 slight left, 4 slight right, 5 u-turn, 6 arrived),
  road name (max 20 chars)

## Build

1. Open `companion-app/` in Android Studio (Arctic Fox or newer; it will generate the Gradle wrapper)
2. Sync and run on a real device (BLE + GPS don't work on emulator)

## First run setup

1. Tap **Enable Notification Access** and allow it for Navio
   (this is how order notifications from Swiggy/Zomato are read — the user's own data, with their permission)
2. Power on the ESP32, tap **Connect to ESP32** (scans for the Nordic UART service)
3. Wait for a Swiggy/Zomato order notification, or paste an address manually
4. Tap **Start Ride**

## Known package names

- Swiggy (customer app): `com.swiggy.gulerix`
- Swiggy Partner: `in.swiggy.android`
- Zomato: `com.application.zomato`

To find the exact package on your phone:
`adb shell pm list packages | grep -E "swiggy|zomato"`

## Tests

`./gradlew test` runs unit tests for the order parser, route math, and geo math
(no device needed).

`verify/logic-mirror.test.js` is a Node mirror of the same pure logic
(parser, math, BLE chunking) — run `node verify/logic-mirror.test.js` without
Gradle to sanity-check the algorithms.

## Out of scope (future phases)

- Offline maps (pre-downloaded Valhalla/OSRM extracts)
- Voice guidance (Tamil/Hindi)
- Trip logging / rider metrics dashboard

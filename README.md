# AndroidRawBT: print from a web POS to a Bluetooth thermal printer on Android

**English** | [Bahasa Indonesia](README.id.md)

**AndroidRawBT is a free, open-source, ad-free Android app that lets a browser-based POS print ESC/POS receipts to a paired Bluetooth thermal printer.** A foreground service listens on `http://127.0.0.1:8080/` and `ws://127.0.0.1:8080/` by default (port configurable), takes raw ESC/POS bytes and writes them to a Bluetooth Classic RFCOMM (SPP) socket. No print dialog, no account, no telemetry.

It is the Android companion of [WinRawBT](https://github.com/Dzul27/WinRawBT) and exposes the **same local print API**, so one POS script works on both platforms. Not affiliated with or endorsed by the RawBT project.

> **Status: beta (v0.1.1-beta).** Behaviour on real printers and on Chrome for Android is pending on-device verification. WinRawBT has only confirmed an EPPOS 58 mm printer so far.

## Features

- Lists paired Bluetooth devices, or scans (~12 s) and pairs new ones through the Android system pairing dialog (the app never guesses a PIN).
- Test Connection prints a diagnostic receipt.
- Local HTTP `POST`, WebSocket and `rawbt:` deep link, all through one serialized FIFO queue.
- Accepts raw bytes, Base64, `rawbt:base64,...` / `intent:base64,...` bodies, `data:` URIs, JSON wrappers, URL-encoded/multipart forms, hex text.
- Connect-on-demand RFCOMM: socket opened per job, one retry, chunked writes (128 bytes, 15 ms pause), size-scaled drain (3.8-10 s), then closed.
- Origin control (`AllowAnyOrigin`, `AllowedOrigins`), Chrome Local Network Access preflight headers.
- Activity log (memory only), service health indicator, optional auto-start, English and Indonesian UI.

## Setup in 60 seconds

1. Pair your printer in Android Bluetooth settings (or use **Scan & Pair** in the app).
2. Download the APK from [Releases](../../releases) and install it.
3. Open the app, grant the Bluetooth and notification permissions, and follow the onboarding.
4. Pick your printer, tap **Test Connection**, then **Start Service**.
5. Print from your web POS to `http://localhost:8080/`.

Requires Android 8.0+ (`minSdk` 26).

## API

Identical to WinRawBT. Endpoint: `POST http://localhost:8080/`

- **Base64 / RawBT text:** `Content-Type: text/plain`
- **Raw binary:** `Content-Type: application/octet-stream`

| Status | Body |
| --- | --- |
| `200` | `{"status": "OK", "bytesSent": 248}` |
| `400` | `{"status": "ERROR", "message": "Empty payload or invalid Base64"}` |
| `403` | Origin rejected (`AllowAnyOrigin=false` and origin not listed) |
| `500` | `{"status": "ERROR", "message": "Bluetooth connection failed: <reason>"}` |

`GET /` returns the service status.

```bash
# Base64
curl -X POST http://localhost:8080/ -H "Content-Type: text/plain" -d "G0AbYQFB..."
# Raw bytes
curl -X POST http://localhost:8080/ -H "Content-Type: application/octet-stream" --data-binary @receipt.bin
```

```js
// fetch, raw bytes
await fetch("http://localhost:8080/", {
  method: "POST",
  headers: { "Content-Type": "application/octet-stream" },
  body: new Uint8Array([0x1b, 0x40, ...new TextEncoder().encode("Hello\n"), 0x1d, 0x56, 0x42, 0x00]),
});

// WebSocket
const ws = new WebSocket("ws://localhost:8080/");
ws.onopen = () => ws.send("G0AbYQFB...");
```

A link such as `rawbt:base64,G0AbYQFB...` opened by the browser or another app is decoded and printed through the same queue (if RawBT is also installed, Android shows its app chooser).

## 58 mm vs 80 mm paper

The app forwards your bytes unchanged. **Formatting the receipt for 58 mm (32 columns) or 80 mm (48 columns) paper is the POS's responsibility.** The *Paper width* setting only sizes the Test Connection receipt. Automatic width detection and layout conversion are out of scope.

## Chrome Local Network Access

Since Chrome 142, a website calling a loopback address makes Chrome show a **permission prompt**; Chrome 156 removed the temporary enterprise opt-out. The server cannot bypass this. It sends the required preflight headers (e.g. `Access-Control-Allow-Private-Network: true`) and the WebSocket endpoint stays usable.

- Choose **Allow** when Chrome asks on first use of your POS.
- If you denied it, the request is blocked inside the browser and **nothing appears in the app log**. Open Chrome's site settings for the POS site and re-allow **Local network access**.
- Exact prompt behaviour on Chrome for Android is **pending on-device verification**.

## Permissions

| Permission | Why |
| --- | --- |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (`neverForLocation`) | List paired printers, scan, pair, connect (Android 12+) |
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (max SDK 30) | Same, on Android 8-11 (location only for discovery) |
| `INTERNET` | The loopback server |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the bridge running |
| `POST_NOTIFICATIONS` | Show service status and Stop action (Android 13+); the service still runs if denied, but the notification may be hidden |
| `RECEIVE_BOOT_COMPLETED` | Optional start on boot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask for the battery-optimization exemption |

No location on Android 12+, no storage, no contacts, no analytics. Print payloads are never persisted.

## Battery and OEM background limits

Doze and OEM task killers (Xiaomi, Oppo/Realme, Vivo, Samsung, Huawei) can stop the service. Grant the battery-optimization exemption during onboarding and follow the per-OEM guidance linked in Settings. If the system stops it, the main screen shows **Stopped by system** with a one-tap restart. Auto-start on boot is off by default; boot-start behaviour per Android version is pending verification.

## Security

The server binds to `127.0.0.1` only, but **loopback is device-wide: any other app on the same device can reach it and print.** `AllowedOrigins` only protects against browsers (a request with an unlisted `Origin` gets HTTP 403; requests without `Origin` are always accepted). Token authentication is deferred. Origins are exact (scheme + host + port).

## Troubleshooting

| Symptom | Meaning / fix |
| --- | --- |
| `[ERROR] Printer unreachable or powered off.` (HTTP 500) | Power on the printer, move closer. |
| Printer asleep | The app wakes it and retries once; the log shows the retry. |
| Port already in use | Another app or instance holds the port; change the port while stopped. |
| Bluetooth is off | Jobs fail with HTTP 500; enable Bluetooth from the shortcut in the app. |
| `Permission missing` | Permission revoked while running; tap the status to open app settings. |
| `Stopped by system` | OS killed the service; tap restart and review battery guidance. |
| Printer not in list / `[not paired]` | Use Scan & Pair; on failure the previous printer is restored and a Bluetooth-settings shortcut is offered. |
| No log line, POS cannot print | Chrome Local Network Access was denied; re-allow it in site settings. |
| `[WARN] Rejected request from origin "..."` (403) | Add the origin to `AllowedOrigins` or enable `AllowAnyOrigin` in Settings. |
| `rawbt:` link prints nothing | Invalid payload; a toast and log entry are shown. |

## Build from source

See [docs/BUILD.md](docs/BUILD.md).

## Parity with WinRawBT

| | WinRawBT | AndroidRawBT |
| --- | --- | --- |
| Endpoints | `http://localhost:8080/`, `ws://localhost:8080/` | same (loopback `127.0.0.1`) |
| Payload decoder, CORS/origin rules, queue | yes | same semantics |
| Connect-on-demand RFCOMM | yes | yes (+ one retry, chunked writes, size-scaled drain) |
| `rawbt:` links | URL payloads accepted | deep-link `intent-filter` for `rawbt:` |
| Paper width | 58 / 80 mm test receipt | 58 / 80 mm test receipt |
| Auto-start | with Windows | on boot / on app open |
| Languages | EN, ID | EN, ID |
| Confirmed printers | EPPOS 58 mm | none yet (pending on-device verification) |

Out of scope: BLE printers, image/HTML rasterizing, multi-printer routing, USB and Wi-Fi printers, token authentication.

## License

MIT, same as WinRawBT. See [LICENSE](LICENSE). Changes are listed in [CHANGELOG.md](CHANGELOG.md). Printer reports: use the *Printer compatibility report* issue template.

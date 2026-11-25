# Changelog

All notable changes to AndroidRawBT. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

## [0.1.4-beta]

### Fixed
- Jobs logged as printed while the paper stayed blank on some cheap printers: the app now waits 500 ms after the Bluetooth link opens before sending the first byte.

### Added
- Settings: "Bluetooth connection method" (Standard, Alternative 1 insecure, Alternative 2 channel 1). If a job is logged as printed but nothing prints, try another method and run Test Connection. The log names the method used for each job.

## [0.1.3-beta]

### Fixed
- Printing from Chrome via the `rawbt:` deep link no longer switches the screen to AndroidRawBT: `DeepLinkActivity` is now a no-display trampoline with its own empty task affinity (it previously shared the app's task and raised MainActivity). Jobs are still queued and logged; the HTTP `127.0.0.1` route with the service running remains the most robust zero-switch path.

## [0.1.2-beta]

### Fixed
- Web prints sent as `rawbt:data:application/octet-stream;base64,...` (and `rawbt://`, `intent:` wrappers) were printed as the literal ASCII text of the data URI (junk on the printer); the decoded inner payload is now run through the decoder again (data URI/base64/hex/JSON, max 4 nested levels), with %2B/%2F/%3D handled correctly and a literal '+' preserved. A recognisably wrapped but invalid inner payload is now rejected instead of printed as text.

## [0.1.1-beta]

### Fixed
- Garbled/junk output on Bluetooth thermal printers for web-originated receipts (~1 KB+): payload is now written in 128-byte chunks with a 15 ms pause and flushed per chunk, and the socket is kept open for a size-scaled drain (min 3.8 s, max 10 s) before closing, instead of one write plus a 250 ms drain.
- Payload decoder no longer trims ESC/POS command bytes FS/GS/RS/US (U+001C-U+001F) as whitespace; JSON `\u` escapes now require exactly four hex digits (parity with WinRawBT).

### Added
- Activity log shows the received body head, an ESC/POS summary (code page, raster/QR/cut counts, bytes >= 0x80 outside images) and a 48-byte hex+ASCII preview.

## [0.1.0-beta]

First public beta (Phase 1 + Phase 2 of the PRD). On-device verification on real printers and Chrome for Android is still pending.

### Added
- Foreground service (`connectedDevice`) with a persistent notification (status, port, Stop action) serving `http://127.0.0.1:<port>/` and `ws://127.0.0.1:<port>/` (default 8080, configurable 1024-65535 while stopped). Bound to loopback only.
- Shared payload decoder, functionally identical to WinRawBT's: raw bytes, Base64 (URL-safe, wrapped, unpadded), `data:` URIs, JSON wrappers, URL-encoded/multipart forms, hex text, `rawbt:base64,...` and `intent:base64,...#Intent;...;end;`.
- CORS, `AllowAnyOrigin` / `AllowedOrigins` (HTTP 403 for unlisted browser origins) and Chrome Local Network Access preflight headers.
- WebSocket listener on the same port.
- Serialized FIFO print queue; connect-on-demand RFCOMM sockets (SPP) with one retry and a ~250 ms drain before closing.
- `rawbt:` deep link.
- Compose UI: paired-printer list, Refresh, Scan & Pair (system pairing dialog), Test Connection, Start/Stop, activity log, Settings (paper width 58/80 mm, origin control, language, battery guidance, About).
- Auto-start switches (on boot, on app open; both default off) and a service health indicator (`Running` / `Stopped` / `Stopped by system`) with one-tap restart.
- Onboarding: Bluetooth and notification permissions, battery-optimization exemption with OEM guidance, Chrome Local Network Access note.
- English and Indonesian localization.
- Signed APK on GitHub Releases (pre-release), built by CI.

# Changelog

All notable changes to AndroidRawBT. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

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

# AndroidRawBT: cetak dari POS web ke printer thermal Bluetooth di Android

[English](README.md) | **Bahasa Indonesia**

**AndroidRawBT adalah aplikasi Android gratis, open-source, tanpa iklan yang memungkinkan POS berbasis browser mencetak struk ESC/POS ke printer thermal Bluetooth yang sudah dipasangkan.** Foreground service mendengarkan di `http://127.0.0.1:8080/` dan `ws://127.0.0.1:8080/` secara default (port dapat diubah), menerima byte ESC/POS mentah, lalu menulisnya ke socket Bluetooth Classic RFCOMM (SPP). Tanpa dialog cetak, tanpa akun, tanpa telemetri.

Ini pendamping Android dari [WinRawBT](https://github.com/Dzul27/WinRawBT) dengan **API lokal yang sama**, sehingga satu skrip POS berjalan di kedua platform. Tidak berafiliasi dengan proyek RawBT.

> **Status: beta (v0.1.2-beta).** Perilaku pada printer asli dan Chrome untuk Android masih menunggu verifikasi di perangkat. WinRawBT baru mengonfirmasi printer EPPOS 58 mm.

## Fitur

- Menampilkan perangkat Bluetooth yang sudah dipasangkan, atau memindai (~12 dtk) dan memasangkan perangkat baru lewat dialog pairing sistem Android (aplikasi tidak menebak PIN).
- Tombol Test Connection mencetak struk diagnostik.
- HTTP `POST`, WebSocket, dan deep link `rawbt:` lokal, semuanya lewat satu antrean FIFO berurutan.
- Menerima byte mentah, Base64, `rawbt:base64,...` / `intent:base64,...`, `data:` URI, pembungkus JSON, form URL-encoded/multipart, teks hex.
- RFCOMM connect-on-demand: socket dibuka per job, satu kali coba ulang, tulis per chunk (128 byte, jeda 15 ms), jeda drain sesuai ukuran (3,8-10 dtk), lalu ditutup.
- Kontrol origin (`AllowAnyOrigin`, `AllowedOrigins`), header preflight Chrome Local Network Access.
- Log aktivitas (hanya di memori), indikator kesehatan service, auto-start opsional, UI Inggris dan Indonesia.

## Pengaturan dalam 60 detik

1. Pasangkan printer di pengaturan Bluetooth Android (atau gunakan **Scan & Pair** di aplikasi).
2. Unduh APK dari [Releases](../../releases) lalu pasang.
3. Buka aplikasi, beri izin Bluetooth dan notifikasi, ikuti onboarding.
4. Pilih printer, ketuk **Test Connection**, lalu **Start Service**.
5. Cetak dari POS web ke `http://localhost:8080/`.

Membutuhkan Android 8.0+ (`minSdk` 26).

## API

Identik dengan WinRawBT. Endpoint: `POST http://localhost:8080/`

- **Base64 / teks RawBT:** `Content-Type: text/plain`
- **Biner mentah:** `Content-Type: application/octet-stream`

| Status | Isi |
| --- | --- |
| `200` | `{"status": "OK", "bytesSent": 248}` |
| `400` | `{"status": "ERROR", "message": "Empty payload or invalid Base64"}` |
| `403` | Origin ditolak (`AllowAnyOrigin=false` dan origin tidak terdaftar) |
| `500` | `{"status": "ERROR", "message": "Bluetooth connection failed: <alasan>"}` |

`GET /` mengembalikan status service.

```bash
curl -X POST http://localhost:8080/ -H "Content-Type: text/plain" -d "G0AbYQFB..."
curl -X POST http://localhost:8080/ -H "Content-Type: application/octet-stream" --data-binary @receipt.bin
```

```js
await fetch("http://localhost:8080/", {
  method: "POST",
  headers: { "Content-Type": "application/octet-stream" },
  body: new Uint8Array([0x1b, 0x40, ...new TextEncoder().encode("Hello\n"), 0x1d, 0x56, 0x42, 0x00]),
});

const ws = new WebSocket("ws://localhost:8080/");
ws.onopen = () => ws.send("G0AbYQFB...");
```

Tautan seperti `rawbt:base64,G0AbYQFB...` yang dibuka browser atau aplikasi lain didekode dan dicetak lewat antrean yang sama (jika RawBT juga terpasang, Android menampilkan pemilih aplikasi).

## Kertas 58 mm vs 80 mm

Aplikasi meneruskan byte Anda apa adanya. **Menata struk untuk kertas 58 mm (32 kolom) atau 80 mm (48 kolom) adalah tanggung jawab POS.** Pengaturan *Paper width* hanya mengatur ukuran struk Test Connection. Deteksi lebar otomatis dan konversi tata letak tidak termasuk cakupan.

## Chrome Local Network Access

Sejak Chrome 142, situs yang memanggil alamat loopback memicu **prompt izin**; Chrome 156 menghapus opt-out sementara untuk enterprise. Server tidak dapat menghindarinya. Server mengirim header preflight yang diperlukan (mis. `Access-Control-Allow-Private-Network: true`) dan WebSocket tetap dapat dipakai.

- Pilih **Izinkan** saat Chrome bertanya pada pemakaian pertama POS Anda.
- Jika sudah ditolak, permintaan diblokir di dalam browser dan **tidak ada baris di log aplikasi**. Buka pengaturan situs Chrome untuk situs POS dan izinkan kembali **Local network access**.
- Perilaku prompt persis di Chrome untuk Android **masih menunggu verifikasi di perangkat**.

## Izin

| Izin | Alasan |
| --- | --- |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (`neverForLocation`) | Daftar printer, pindai, pairing, koneksi (Android 12+) |
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (maks SDK 30) | Sama, pada Android 8-11 (lokasi hanya untuk pencarian) |
| `INTERNET` | Server loopback |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Menjaga bridge tetap berjalan |
| `POST_NOTIFICATIONS` | Status service dan tombol Stop (Android 13+); service tetap jalan jika ditolak, tetapi notifikasi bisa tersembunyi |
| `RECEIVE_BOOT_COMPLETED` | Start saat boot (opsional) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Meminta pengecualian optimasi baterai |

Tanpa lokasi di Android 12+, tanpa penyimpanan, tanpa kontak, tanpa analitik. Payload cetak tidak pernah disimpan.

## Baterai dan batasan background OEM

Doze dan pembunuh tugas OEM (Xiaomi, Oppo/Realme, Vivo, Samsung, Huawei) dapat menghentikan service. Berikan pengecualian optimasi baterai saat onboarding dan ikuti panduan per OEM di Pengaturan. Jika dihentikan sistem, layar utama menampilkan **Stopped by system** dengan tombol restart satu ketukan. Auto-start saat boot nonaktif secara default; perilaku start saat boot per versi Android masih menunggu verifikasi.

## Keamanan

Server hanya mengikat `127.0.0.1`, tetapi **loopback berlaku untuk seluruh perangkat: aplikasi lain di perangkat yang sama dapat mengaksesnya dan mencetak.** `AllowedOrigins` hanya melindungi dari browser (permintaan dengan `Origin` tak terdaftar mendapat HTTP 403; permintaan tanpa `Origin` selalu diterima). Autentikasi token ditunda. Origin bersifat persis (skema + host + port).

## Pemecahan masalah

| Gejala | Arti / solusi |
| --- | --- |
| `[ERROR] Printer unreachable or powered off.` (HTTP 500) | Nyalakan printer, dekatkan. |
| Printer tidur | Aplikasi membangunkannya dan mencoba ulang sekali; log menampilkan percobaan ulang. |
| Port sudah dipakai | Aplikasi/instance lain memakai port; ubah port saat service berhenti. |
| Bluetooth mati | Job gagal dengan HTTP 500; aktifkan Bluetooth lewat pintasan di aplikasi. |
| `Permission missing` | Izin dicabut saat berjalan; ketuk status untuk membuka pengaturan aplikasi. |
| `Stopped by system` | OS menghentikan service; ketuk restart dan baca panduan baterai. |
| Printer tidak ada di daftar / `[not paired]` | Gunakan Scan & Pair; jika gagal, printer sebelumnya dipulihkan dan ada pintasan ke pengaturan Bluetooth. |
| Tidak ada baris log, POS tidak bisa mencetak | Chrome Local Network Access ditolak; izinkan kembali di pengaturan situs. |
| `[WARN] Rejected request from origin "..."` (403) | Tambahkan origin ke `AllowedOrigins` atau aktifkan `AllowAnyOrigin` di Pengaturan. |
| Tautan `rawbt:` tidak mencetak | Payload tidak valid; toast dan entri log ditampilkan. |

## Build dari source

Lihat [docs/BUILD.md](docs/BUILD.md).

## Paritas dengan WinRawBT

| | WinRawBT | AndroidRawBT |
| --- | --- | --- |
| Endpoint | `http://localhost:8080/`, `ws://localhost:8080/` | sama (loopback `127.0.0.1`) |
| Decoder payload, aturan CORS/origin, antrean | ya | semantik sama |
| RFCOMM connect-on-demand | ya | ya (+ satu coba ulang, tulis per chunk, jeda drain sesuai ukuran) |
| Tautan `rawbt:` | payload URL diterima | `intent-filter` deep link `rawbt:` |
| Lebar kertas | struk uji 58 / 80 mm | struk uji 58 / 80 mm |
| Auto-start | bersama Windows | saat boot / saat aplikasi dibuka |
| Bahasa | EN, ID | EN, ID |
| Printer terkonfirmasi | EPPOS 58 mm | belum ada (menunggu verifikasi di perangkat) |

Di luar cakupan: printer BLE, rasterisasi gambar/HTML, routing multi-printer, printer USB dan Wi-Fi, autentikasi token.

## Lisensi

MIT, sama dengan WinRawBT. Lihat [LICENSE](LICENSE). Perubahan ada di [CHANGELOG.md](CHANGELOG.md). Laporan printer: gunakan template isu *Printer compatibility report*.

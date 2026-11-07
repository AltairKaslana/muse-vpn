# Muse VPN — browser satu situs + WireGuard per-aplikasi

Aplikasi Android kecil: membuka **hanya `https://muse.ai`** di dalam WebView, dan semua
trafik aplikasi ini keluar lewat **tunnel WireGuard** (mis. server US).

## Cara pakai
1. Pasang APK.
2. Siapkan file `.conf` WireGuard (dari server US: VPS sendiri, Oracle free tier, atau provider).
3. Buka app → **Impor .conf** → pilih file.
4. Setujui dialog izin VPN Android (sekali saja).
5. Setelah status **VPN aktif**, situs dimuat. Tombol **Keluar** memutus tunnel.

## Yang sudah ditangani (beda dari MVP sebelumnya)
| Celah MVP | v1 |
|---|---|
| Tunnel drop → browser lanjut pakai IP asli | **Kill switch**: tunnel turun → halaman dihentikan, semua request http(s) diblokir |
| Tanpa foreground service | `TunnelFgs` bertipe `specialUse` (syarat Android 14+) |
| Private key tersimpan plaintext | Disimpan **AES-256-GCM dengan kunci Android Keystore** |
| Host hardcoded tanpa validasi | Host dikunci `muse.ai` + subdomain, https saja, link luar diblokir |
| Video fullscreen tidak jalan | `WebChromeClient.onShowCustomView` ditangani |
| Library WireGuard versi lama | `1.0.20260102` |

## Batasan
- Android hanya mengizinkan **satu VPN aktif**: selama aplikasi ini jalan, Tailscale/Windscribe mati.
- Aplikasi lain **tidak** ikut lewat VPN (memang begitu desainnya — `IncludedApplications`).
- Untuk kill switch level sistem: aktifkan **Always-on VPN + Block connections without VPN** di Setelan Android untuk app ini.
- WebView bukan browser penuh: unduhan file, DRM, dan beberapa API bisa tidak jalan.
- `allowBackup=false`, tanpa iklan, tanpa telemetri.

## Ganti situs
Ubah `SITE_HOST` / `SITE_URL` di `app/build.gradle.kts`, lalu build ulang.

## Build
Push ke `main` → GitHub Actions menghasilkan `app-release.apk` sebagai artifact.
Tag `v*` → APK dipublikasikan sebagai GitHub Release.
Butuh secrets: `MUSE_KEYSTORE_BASE64`, `MUSE_KEY_ALIAS`, `MUSE_KEYSTORE_PASSWORD`, `MUSE_KEY_PASSWORD`.

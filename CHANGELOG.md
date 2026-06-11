# Changelog - NaturalAuth 🔐

Dokumentasi riwayat pembaruan, perbaikan bug, dan rilis fitur untuk plugin **NaturalAuth** (Velocity + Paper).

---

## [Unreleased] / [v1.2.0] - Premium & Limbo Waiting Room Update
### ✨ Fitur Baru
- **Limbo Waiting Room**: Logika ruang tunggu cerdas yang menangkap player ketika server utama (School/Main) mati/restart. Menampung player di lobby dengan gameplay pasif dan memindahkan mereka kembali secara otomatis saat server online.
- **Server Selector Compass**: Memberikan item Kompas Selector di slot inventory ke-4 setelah terautentikasi. Klik kanan membuka chest GUI `⚡ Server Selector ⚡` untuk berpindah server secara instan.
- **GameMode Survival Force**: Memaksa semua player di lobby untuk menggunakan mode Survival (mengunci Creative/Adventure) demi konsistensi.
- **Console Command Execution**: Seluruh command admin (`/na admin ...`) kini sepenuhnya dapat dijalankan dari Console Server.
- **Virtual Void Lobby Mode**: Pembatasan pergerakan, event, dan interaksi bagi player yang belum login maupun yang sedang di dalam Limbo.
- **PlaceholderAPI & ItemsAdder Emojis**: Dukungan parsing placeholder dan render emoji kustom pada dialog GUI masuk/daftar.
- **Deep Config Reload**: Command `/na admin reload` untuk memuat ulang seluruh database pool, cache, dan file konfigurasi secara mendalam.

### ⚡ Peningkatan & Refactor
- **Optimasi Efek Lock**: Efek buta (blindness) dan lambat (slowness) hanya ditahan selama 3 detik setelah login berhasil sebelum dihapus total.
- **Virtual Void Lobby FX**: Menyembunyikan ikon efek ramuan (HUD potion icons) dan partikel agar tampilan layar tetap bersih dan premium.
- **Sound & Ambient Effects**: Efek suara chimes amethyst, partikel penyihir (witch particles), dan pesan dinamis di BossBar/ActionBar selama masa tunggu Limbo.
- **Self-Healing State Sync**: Protokol sinkronisasi otomatis status autentikasi antara server Paper (backend) dan proxy Velocity untuk mencegah desinkronisasi sesi.

### 🐛 Perbaikan Bug
- **Redirect Loop Fix**: Mencegah loop pengalihan tanpa batas dengan melakukan kick otomatis ke player jika server lobby sedang offline.
- **Session Auto-Login Fix**: Menunda auto-login hingga event `PACKET_PLAYER_READY` diterima untuk menjamin kelancaran client loading.
- **Particle Compatibility**: Update `Particle.SPELL_WITCH` ke `Particle.WITCH` untuk menjaga kompatibilitas penuh dengan Paper/Spigot 1.21+.

---

## [v1.2.1] - 2026-06-11 — Security Hotfix: Premium Username Collision
### 🐛 Perbaikan Bug Kritis
- **Premium Username Collision Fix**: Memperbaiki bug di mana crack player yang menggunakan username yang kebetulan sama dengan akun Mojang premium dipaksa melalui autentikasi online-mode dan dikick secara permanen tanpa pesan yang jelas.
- **Logika `onPreLogin` Direfaktor Total**: Pemisahan bersih antara dua jalur validasi:
  - **Player sudah di DB & `premium=1`** → `forceOnlineMode()` (sudah eksplisit opt-in via `/premium confirm`)
  - **Player baru (belum di DB) & nama ada di Mojang** → `forceOnlineMode()` agar Velocity memverifikasi session token secara langsung — player premium asli lolos, crack player dikick oleh Velocity sendiri
  - **Player baru, nama tidak ada di Mojang** → offline/crack flow, muncul GUI register
- **Anti-Username Squatting**: Mempertahankan pengecekan Mojang API untuk player baru guna mencegah crack player mendaftarkan slot username milik akun premium orang lain.

### ⚡ Peningkatan
- **Log Lebih Informatif**: Seluruh log di `onPreLogin` dan `onPostLogin` kini mencantumkan prefix `[NaturalAuth]`, UUID player, dan status `onlineMode` untuk memudahkan debugging.
- **Komentar Inline Komprehensif**: Setiap cabang logika `onPreLogin` diberi penjelasan alasan teknis dan potensi exploit yang dimitigasi agar mudah dipelihara di masa depan.

---

## [v1.1.0] - UX & Security Update
### ✨ Fitur Baru
- **Premium Bypass & Auto-Detection**: Sistem bypass otomatis untuk player original (Java Premium) dan Bedrock Edition (via Floodgate/Geyser) tanpa perlu mengetik `/login`.
- **Email & OTP Integration**: Fitur `/email <alamatEmail>` untuk registrasi awal dan pemulihan password. Integrasi OTP dengan AnvilGUI premium untuk validasi kode.
- **Whois Chest GUI**: GUI khusus admin untuk melacak informasi player (IP, UUID, email link, status autentikasi, ping, dll).
- **Native Notice Dialogs**: Menampilkan pesan kesalahan autentikasi dan opsi lupa sandi via dialog UI native client-side.
- **Brute Force Protection**: Pembatasan maksimal 5 kali percobaan login salah berturut-turut sebelum dikenai cooldown 60 detik atau kick otomatis.

### ⚡ Peningkatan & Refactor
- **Asynchronous BCrypt**: Pemrosesan enkripsi dan hashing sandi dipindahkan ke thread async agar tidak membebani main thread server TPS.
- **SQL Sanitization**: Validasi parameterisasi penuh pada seluruh query database untuk mencegah celah SQL Injection.
- **Lobby Join Delay**: Menambahkan jeda kecil sebelum pengalihan otomatis agar data skin dan cache server ter-load dengan sempurna.

### 🐛 Perbaikan Bug
- **PreLoginComponentResult Typo**: Memperbaiki typo internal pemanggilan event login pada proxy Velocity.
- **Double Input Error**: Mengatasi error method signature `DialogInput.text` dengan penanganan nilai multiline null.

---

## [v1.0.0] - Initial Release
- Inisialisasi arsitektur terpisah (Split Architecture) Velocity (Proxy Core) dan Paper (Backend UI).
- Koneksi MySQL dengan pool koneksi HikariCP.
- Sistem Session Recovery berbasis kombinasi IP Address & UUID player.

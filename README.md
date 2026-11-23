# fraud-guard

**Demo langsung:** <https://fraud-guard-465d7.containers.snapdeploy.app> · **Halaman proyek:** <https://fraud.kadaferhidup.my.id>

> Server demo memakai paket gratis SnapDeploy yang tidur setelah 15 menit tanpa pengunjung. Kunjungan pertama menampilkan halaman "Waking up your container" sekitar satu menit, lalu konsolnya terbuka sendiri.

Mesin **anti-fraud dan anti pencucian uang (AML)** untuk bank. Sistem core banking mengirim setiap transaksi ke fraud-guard, lalu fraud-guard menjawab dalam satu permintaan:

- **skor risiko 0–100**, dengan setiap poin dijelaskan oleh aturan yang memicunya;
- **keputusan**: `ALLOW` (jalankan), `REVIEW` (tahan dana, serahkan ke analis), atau `BLOCK` (tolak);
- **kasus** untuk analis kalau transaksinya ditahan atau diblokir;
- **laporan PPATK**: LTKT dibuat otomatis untuk transaksi tunai Rp500 juta ke atas, LTKM disusun setelah supervisor menyetujui usulan analis.

Konsol web menyediakan sandbox per pengunjung: satu bank kecil dengan tiga nasabah (semuanya atas nama Ahmad Umraithi) dan riwayat transaksi 30 hari, lengkap dengan peran analis dan supervisor.

Dibuat oleh **Ahmad Umraithi** (Teknik Informatika, Universitas Malikussaleh) untuk belajar dan portofolio. Daftar pantauan dan angka ambang di sini adalah simulasi, bukan data atau ketentuan resmi.

## Aturan penilaian

Setiap aturan adalah pemeriksaan biasa dengan poin tetap dan kalimat penjelasan. Tidak ada kotak hitam: analis dan auditor bisa melihat persis bagaimana skor terbentuk.

| Aturan | Poin | Kapan terpicu |
|---|---|---|
| `WATCHLIST` | blokir | Rekening lawan ada di daftar pantauan (simulasi DTTOT) |
| `ACCOUNT_FROZEN` | blokir | Rekening sudah dibekukan setelah kasus fraud dikonfirmasi |
| `STRUCTURING` | 45 | Tiga setoran tunai atau lebih dalam 24 jam, masing-masing di bawah batas lapor, totalnya mencapai batas |
| `VELOCITY` | 25 / 40 | 5 / 10 transaksi keluar dalam 10 menit |
| `PASS_THROUGH` | 35 | Dana masuk lalu ≥80% langsung keluar dalam 1 jam (pola rekening penampung) |
| `UNUSUAL_AMOUNT` | 25 | Lebih dari 5× rata-rata transaksi keluar 30 hari |
| `NEW_ACCOUNT` | 20 | Rekening berumur kurang dari 7 hari mengirim ≥ Rp10 juta |
| `INCOME_MISMATCH` | 20 | Dana keluar 30 hari lebih dari 3× penghasilan yang dilaporkan |
| `LARGE_AMOUNT` | 15 | Nominal ≥ Rp100 juta |
| `NEW_COUNTERPARTY` | 15 | Pertama kali mengirim ≥ Rp25 juta ke rekening ini |
| `NEW_DEVICE` | 15 | Perangkat belum pernah dipakai, nominal ≥ Rp5 juta |
| `JUST_BELOW_REPORT` | 15 | Setoran tunai 90–100% dari batas lapor |
| `ODD_HOUR` | 10 | Pukul 00.00–03.59 WIB, nominal ≥ Rp10 juta |
| `HIGH_RISK_CUSTOMER` | 10 | Nasabah berprofil risiko tinggi |

Skor 40 ke atas ditahan (`REVIEW`), 70 ke atas diblokir (`BLOCK`). Semua angka ada di satu tempat (`Thresholds.java`).

## Yang dijaga

| Risiko | Cara fraud-guard menanganinya |
|---|---|
| Burst transaksi paralel lolos dari aturan velocity | Transaksi satu rekening dinilai bergiliran di bawah kunci rekening. Diuji dengan 12 transaksi serentak: tepat 3 yang melewati batas ditahan, berapa pun urutannya. |
| Keputusan ditimpa | ID transaksi unik. Kiriman ulang yang sama mendapat jawaban pertama; ID sama dengan isi berbeda ditolak `409 DUPLICATE_ID`. |
| Satu orang memutus kasusnya sendiri | Prinsip 4 mata: hanya analis yang memegang kasus boleh mengusulkan, hanya supervisor boleh menyetujui, dan penyetuju tidak boleh sama dengan pengusul. |
| Dua analis memproses kasus yang sama | Setiap langkah memeriksa status terbaru di bawah kunci kasus. Diuji dengan 8 klaim serentak: hanya satu yang berhasil. |
| Kredensial dipakai di luar fungsinya | Tiga rahasia terpisah per sandbox: API key hanya untuk mengirim transaksi, token analis untuk memeriksa dan mengusulkan, token supervisor untuk menyetujui dan membaca audit. Yang disimpan hanya hash SHA-256-nya. |
| Data nasabah bank lain terlihat | Setiap sandbox terisolasi. Token sandbox A tidak bisa membaca atau mengambil kasus sandbox B. |
| Jejak audit diubah diam-diam | Log audit berantai: setiap entri menyimpan hash entri sebelumnya. Mengubah satu entri lama memutus rantai, dan `GET /api/audit` menunjukkan entri mana yang rusak. |
| LTKM dikirim tanpa pemeriksaan | LTKM hanya dibuat setelah usulan analis disetujui supervisor. |
| Dana yang diblokir dihitung sebagai dana masuk | Transaksi yang diblokir tetap dihitung sebagai percobaan (velocity), tapi tidak pernah dihitung sebagai uang yang berpindah. |
| Nominal janggal | Nominal wajib string dua desimal (`"1500000.00"`), disimpan dalam sen sebagai `long`, tidak pernah `float`. |
| Banjir permintaan | Batas per IP untuk seluruh API dan untuk pembuatan sandbox. IP diambil dari entri terakhir `X-Forwarded-For`. |
| XSS di konsol | Semua data ditulis ke halaman sebagai teks, dan Content-Security-Policy melarang skrip maupun style inline. |

## API

| Metode | Path | Siapa | Isi |
|---|---|---|---|
| `POST` | `/api/sandbox` | publik | Sandbox baru: `apiKey`, `analystToken`, `supervisorToken`, daftar rekening |
| `POST` | `/api/transactions` | API key | `id`, `accountNo`, `type` (`TRANSFER_OUT`, `TRANSFER_IN`, `CASH_DEPOSIT`, `CASH_WITHDRAWAL`), `amount`, `counterpartyAccount` (wajib untuk transfer), `channel`, `deviceId`, `at` (opsional, maks. 30 hari lalu) |
| `GET` | `/api/transactions` | analis, supervisor | 30 keputusan terbaru |
| `GET` | `/api/cases?status=OPEN` | analis, supervisor | Antrian kasus |
| `GET` | `/api/cases/{id}` | analis, supervisor | Detail kasus, alasan, riwayat |
| `POST` | `/api/cases/{id}/claim` | analis | Ambil kasus |
| `POST` | `/api/cases/{id}/propose` | analis pemegang kasus | `resolution`: `RELEASE`, `CONFIRM_FRAUD`, atau `REPORT_LTKM`, plus `note` |
| `POST` | `/api/cases/{id}/approve` | supervisor selain pengusul | Jalankan keputusan: lepas dana, bekukan rekening, atau buat LTKM |
| `POST` | `/api/cases/{id}/send-back` | supervisor | Kembalikan ke analis dengan `note` |
| `GET` | `/api/reports` | analis, supervisor | LTKT dan LTKM |
| `GET` | `/api/audit` | supervisor | 100 entri terakhir dan hasil verifikasi rantai |

Contoh:

```bash
curl -s -X POST localhost:8080/api/sandbox -H 'Content-Type: application/json' -d '{}'

curl -s localhost:8080/api/transactions \
  -H "Authorization: Bearer $API_KEY" -H 'Content-Type: application/json' \
  -d '{"id":"TRX-001","accountNo":"7001002003","type":"TRANSFER_OUT","amount":"48000000.00","counterpartyAccount":"7009222222"}'
```

## Menjalankan

### Online

Versi online berjalan di [SnapDeploy](https://snapdeploy.dev) dari `Dockerfile` di repo ini, dan setiap push ke `main` memicu deploy ulang otomatis. Halaman proyek di `docs/` disajikan GitHub Pages dengan domain `fraud.kadaferhidup.my.id` (file `docs/CNAME`).

Semua contoh API di bawah bisa dijalankan ke server demo dengan mengganti `localhost:8080` menjadi `https://fraud-guard-465d7.containers.snapdeploy.app`.

### Di komputer sendiri

Butuh Java 21 dan Maven.

```bash
mvn package
java -jar target/fraud-guard.jar     # buka http://localhost:8080
```

Atau dengan Docker:

```bash
docker build -t fraud-guard .
docker run -p 8080:8080 fraud-guard
```

| Variabel | Default | Keterangan |
|---|---|---|
| `PORT` | `8080` | |
| `RATE_LIMIT_PER_MINUTE` | `300` | Permintaan API per IP per menit |
| `SANDBOX_PER_HOUR` | `10` | Sandbox baru per IP per jam |

Sandbox disimpan di memori dan dihapus setelah 24 jam.

## Test

```bash
mvn verify
```

27 test, termasuk setiap aturan penilaian, structuring, rekening penampung, burst paralel lewat HTTP, alur 4 mata dengan pembekuan rekening, LTKM yang baru muncul setelah persetujuan, isolasi antar sandbox, hak tiap kredensial, ID transaksi ganda, dan audit yang diubah.

## Struktur kode

```
src/main/java/id/my/kadaferhidup/fraudguard/
├── risk/        RiskEngine (aturan), Thresholds, AccountState (riwayat + kunci), Txn, Money
├── cases/       CaseDesk (alur kasus dan 4 mata), CaseFile, Actor, Report
├── audit/       AuditLog (hash berantai)
├── workspace/   Workspace (satu bank sandbox), Workspaces (sandbox dan kredensial), Decision
├── http/        Api (routing, validasi), RateLimiter, StaticFiles
├── json/        Json: penulis dan pembaca JSON kecil
└── App.java
src/main/resources/web/   konsol analis (HTML, CSS, JS tanpa framework)
```

Tanpa framework dan tanpa dependensi runtime: hanya JDK 21.

## Lisensi

MIT

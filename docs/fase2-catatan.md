# Fase 2: Pemilihan Momen dengan AI — catatan (17 November 2026)

## Yang sudah dibangun

- **Modul core** (padanan desktop, dikunci tes `AnalysisParityTest` dari `tools/export-analysis-fixtures.php`, yang
  menjalankan kode desktop asli dengan SQLite di memori):
  - `PromptBuilder`: template bawaan identik, {{judul}}/{{durasi}} (strtr), pemecahan transkrip panjang per batas token
    dengan tumpang-tindih 15 segmen, estimasi token (karakter / 3,5).
  - `ClipImporter`: toleran terhadap teks pembuka/penutup, blok ```json, koma berlebih, root array; ID segmen
    `S0012`/`s12`/`12`; waktu dari kata pertama/terakhir; durasi 10 detik–5 menit; skor 1–100; hashtag dan kata kunci
    (`Keywords::clean`) dari array atau string; seri + nomor bagian; pesan kesalahan sama persis.
  - `ClipText`: judul "… (Bagian N)" dan teks unggahan (judul, caption, hashtag).
  - Tiruan `Str::limit`/`Str::squish` Laravel.
- **Database v3**: `clips`, `clip_series`, `ai_usages`, kolom status analisis di `projects` (nama kolom sama dengan
  desktop) + migrasi 2 → 3.
- **Mode manual**: tiap bagian prompt bisa disalin atau dibagikan ke aplikasi chat AI; tempel balasan (atau "Tempel
  dari clipboard"), kandidat disimpan dengan validasi desktop; klip yang sama dilewati.
- **Mode API** (`AnalyzeWorker`, antrean AI): Anthropic, OpenAI, Gemini, Groq, kompatibel OpenAI; uji koneksi tanpa
  token (daftar model); perkiraan token dan biaya sebelum dijalankan; per bagian dengan sidik prompt, sehingga
  "Lanjutkan" hanya mengirim bagian yang belum berhasil; batalkan; coba ulang sekali untuk 429/5xx; pemakaian dicatat di
  halaman Pemakaian AI (transkripsi API juga dicatat, dalam menit audio). Analisis otomatis setelah transkrip siap
  (opsional, sama dengan desktop: hanya bila project belum punya kandidat).
- **Kandidat klip** (tab Klip di detail project): kartu skor, rentang, durasi, seri, hook, caption, hashtag, kata kunci,
  alasan; urut skor (manual di akhir); filter Aktif/Terpilih/Dibuang; geser kanan = pilih, kiri = buang; putar rentang
  (berhenti sendiri di akhir); sunting teks; salin teks unggahan; hapus kandidat yang belum ditinjau.
- **Klip manual**: di tab Transkrip, tekan lama segmen awal lalu ketuk segmen akhir → "Buat klip" (langsung terpilih).
- Key Anthropic dari layar kerangka Fase 0 dipindah otomatis ke pengaturan Analisis AI.

## Diuji di HP (Xiaomi Mi 8 Lite)

| Uji | Hasil |
|---|---|
| Migrasi database 2 → 3 | `user_version` 3, tabel baru ada, Room menerima skema |
| Prompt project YouTube (226 segmen) | 1 bagian, 33.170 karakter, **identik byte demi byte** dengan PromptBuilder desktop untuk transkrip yang sama |
| Simpan balasan AI (blok ```json, hashtag string, seri 2 bagian, 1 klip terlalu panjang) | 3 kandidat + 1 ditolak; **semua kolom identik** dengan ClipImporter desktop |

| Mode API, Google Gemini `gemini-3.8-flash` | **7 kandidat dalam 31 detik** (10.806 token masuk, 1.815 keluar), durasi 61–88 detik, skor 80–95, kata kunci dari transkrip; tercatat di Pemakaian AI |
| Uji koneksi (tanpa token) | Groq: 11 model; Gemini: 64 model |
| Kegagalan penyedia | Anthropic: key lama ditolak (401), lalu kredit kosong (400); Groq: semua model chat diblokir di pengaturan project (403); Gemini 3.5 Flash sibuk (503, dicoba ulang sekali); Gemini 2.5 Flash tidak tersedia untuk pengguna baru (404). Semua tampil sebagai pesan yang jelas di tab Klip, tanpa token terpakai |

Perbaikan dari uji ini: HTTP 403 karena model diblokir kini berbunyi "Model ini tidak diizinkan untuk akun/project …"
(sebelumnya "API key ditolak"); key Transkripsi API Groq/OpenAI otomatis dipakai untuk analisis penyedia yang sama
bila key analisis kosong.

Belum diuji: tampilan layar (HP terkunci saat pengujian), geser kartu, bagikan prompt ke aplikasi chat AI, transkrip
panjang yang dibagi beberapa bagian lewat Mode API (dan "Lanjutkan").

## Catatan

- Teks lewat clipboard/intent dibatasi ±300 ribu karakter (batas Binder Android). Prompt yang lebih besar (batas bawaan
  120 ribu token ≈ 420 ribu karakter) diminta dipecah lewat "Batas panjang prompt" di Pengaturan → Analisis AI.
- Pengaturan AI di HP disimpan per kunci (`ai.analysis.provider`, `ai.analysis.model`, …) sedangkan desktop menyimpan
  satu JSON `ai.analysis`; API key tidak pernah ikut Paket Standar Tim maupun cadangan, jadi tidak perlu sama.

# 🤖 AI Coder

**Asisten coding AI langsung di HP Android kamu.** Import project (ZIP), ngobrol dengan AI, biarkan AI membaca dan mengedit kodenya, review perubahannya, lalu export lagi. Semua dari satu app, tanpa PC.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Version](https://img.shields.io/badge/version-1.4.0-blue)

## ✨ Keunggulan

- **Gratis sepenuhnya.** Pakai API key gratis dari 6 provider AI, tanpa langganan.
- **Bukan sekadar chatbot.** AI benar-benar bisa menjelajah, membaca, mencari, dan menulis file di project kamu.
- **Aman.** Semua perubahan lewat *Diff Review* dulu, jadi tidak ada yang tertimpa tanpa persetujuan kamu.
- **Fleksibel.** Tidak terkunci di satu provider. Kalau limit satu habis, ganti ke yang lain.
- **Daftar model selalu update.** Model diambil live dari API tiap provider, jadi tidak basi.
- **Ringan.** APK terpisah untuk arm64 dan arm32.

## 🚀 Fitur

- 📦 Import project lewat ZIP atau file tunggal
- 🧠 AI dengan tool-calling: `list_directory`, `read_file`, `search_code`, `write_file`, `rename_file`
- 🔍 Deteksi otomatis struktur project Android (manifest, gradle, res) supaya AI langsung tahu file mana yang harus diedit
- 🧾 Diff Review dengan syntax highlighting sebelum perubahan diterapkan
- 💬 Riwayat chat & project tersimpan, dengan kompaksi history otomatis untuk konteks panjang
- 🎨 Generate gambar (untuk provider yang mendukung)
- 📤 Export hasil ke ZIP, simpan ke Downloads, atau langsung share
- 🔐 API key disimpan aman di perangkat

## 🔌 Provider yang Didukung

| Provider | Catatan Free Tier |
|---|---|
| Google Gemini | Model `*-pro` ketat (~5 RPM), Flash-Lite lebih longgar |
| Groq | 30 request/menit, tanpa kartu kredit |
| OpenRouter | Model `:free`, ~20 request/menit |
| Mistral AI | ~1 request/detik, perlu verifikasi nomor HP |
| Cloudflare Workers AI | 10.000 neuron/hari gratis |
| Hugging Face | Kuota bulanan kecil (credit-based) |

## 📱 Cara Menggunakan

1. Unduh APK dari halaman **Releases** (pilih `arm64` untuk kebanyakan HP modern, `arm32` untuk HP lama).
2. Install, lalu buka **AI Coder**.
3. Masukkan **API key** kamu (cara dapatnya di bawah).
4. Pilih provider dan model.
5. **Import** project ZIP atau file yang mau diedit.
6. Ketik perintah ke AI, misalnya *"tambahkan dark mode di SettingsScreen"*.
7. Cek perubahan di **Diff Review**, lalu **Terapkan & Simpan**.
8. **Export** hasilnya sebagai ZIP.

## 🔑 Cara Dapat API Key Gratis

Cukup pilih **satu** provider. Daftar, buat key, tempel di app.

| Provider | Link | Catatan |
|---|---|---|
| **Groq** ⭐ paling gampang | [console.groq.com/keys](https://console.groq.com/keys) | Login → *Create API Key* |
| **Google Gemini** | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | Login Google → *Create API key* |
| **OpenRouter** | [openrouter.ai/keys](https://openrouter.ai/keys) | Pilih model berakhiran `:free` |
| **Mistral** | [console.mistral.ai/api-keys](https://console.mistral.ai/api-keys) | Perlu verifikasi nomor telepon |
| **Cloudflare** | [dash.cloudflare.com/profile/api-tokens](https://dash.cloudflare.com/profile/api-tokens) | Isi key dengan format `ACCOUNT_ID:API_TOKEN` (token izin *Workers AI*) |
| **Hugging Face** | [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens) | Izin *Make calls to Inference Providers* |

> 💡 **Tips:** Untuk edit project besar (banyak request beruntun), Groq atau OpenRouter biasanya lebih aman dari kena rate limit dibanding Gemini `*-pro`.

## ⚠️ Catatan

- API key disimpan lokal di HP dan hanya dikirim ke provider yang kamu pilih.
- Batas free tier ditentukan masing-masing provider dan bisa berubah sewaktu-waktu.
- Selalu review diff sebelum menerapkan perubahan.

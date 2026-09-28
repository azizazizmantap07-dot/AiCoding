# Build Single Release APK (GitHub Actions)

## 1. Isi GitHub Secrets

Repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| Nama secret | Nilai |
|-------------|--------|
| `RELEASE_KEYSTORE_BASE64` | Isi dari file `keystore/aicoder-release.jks.base64.txt` (satu baris base64) |
| `RELEASE_STORE_PASSWORD` | Password store (dari password manager kamu — jangan ditulis di repo) |
| `RELEASE_KEY_ALIAS` | `aicoder` |
| `RELEASE_KEY_PASSWORD` | Password key (dari password manager kamu — jangan ditulis di repo) |

Cara buat base64 sendiri (jika perlu):

```bash
base64 -w0 keystore/aicoder-release.jks
```

## 2. Jalankan workflow

- Buka tab **Actions** → **Build Release APK** → **Run workflow**, atau
- Push tag: `git tag v1.3.4 && git push origin v1.3.4`

## 3. Unduh APK

Setelah job sukses → buka run → artifact **AI-Coder-release-…** → unduh ZIP → di dalamnya ada:

`AI-Coder-1.3.4-release.apk` (satu file, signed, universal)

## 4. File keystore lokal

Folder `keystore/` (jangan di-commit ke repo publik):

- `aicoder-release.jks` — keystore
- `keystore.properties` — untuk build lokal
- `KEYSTORE-INFO.txt` — ringkasan kredensial (tanpa password)

Build lokal (opsional):

```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

## Optimasi build (v1.3.5+)

- **R8 minify + resource shrink** aktif di release → APK lebih kecil.
- **ABI split**: dua APK (`arm32` / `arm64`), tanpa universal.
- **Gradle**: parallel, build cache, configuration cache, heap 4G.
- **CI**: cache Gradle diaktifkan → run kedua jauh lebih cepat dari ~6 menit cold build.

Pilih APK sesuai perangkat:
- Mayoritas HP modern → `AI-Coder-*-arm64-release.apk`
- HP ARM 32-bit lama → `AI-Coder-*-arm32-release.apk`

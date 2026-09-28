// Top-level build file: deklarasi plugin versi di sini (applied = false),
// dipakai oleh module app/build.gradle.kts.
plugins {
    // Dinaikkan 8.7.2 -> 8.13.2. AGP 8.7.2 membawa R8 8.7.x dan lint analyzer yang
    // dirilis SEBELUM Kotlin 2.2 ada, sehingga build release penuh warning:
    //   - "R8: An error occurred when parsing kotlin metadata"  (minifyReleaseWithR8)
    //   - "Missing analysis API method ..."                     (lintVitalAnalyzeRelease)
    // AGP 8.13.2 membawa R8 8.13.19 yang mendukung metadata Kotlin 2.2/2.3 dan lint
    // yang lebih baru. Butuh Gradle >= 8.13 (lihat workflow / gradle-wrapper).
    // Sengaja TIDAK ke AGP 9.x: mengubah banyak perilaku (Kotlin built-in, properti dihapus).
    id("com.android.application") version "8.13.2" apply false
    // Dinaikkan dari 2.0.21 -> 2.2.21: Ktor 3.5.2 dan kotlinx-coroutines 1.11.0
    // (dependency Tahap 3) dikompilasi dengan metadata Kotlin 2.3.0, sehingga
    // tidak kompatibel dengan compiler 2.0.0 ("Module was compiled with an
    // incompatible version of Kotlin"). 2.2.21 adalah rilis Kotlin 2.2.x
    // terakhir dan sudah mendukung metadata versi tersebut.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    // KSP dibutuhkan Room compiler (Bagian 8 blueprint). Versi KSP harus match
    // persis dengan versi Kotlin 2.2.21 di atas (format: <kotlin>-<ksp-patch>).
    id("com.google.devtools.ksp") version "2.2.21-2.0.4" apply false
    // Dibutuhkan Ktor (ktor-serialization-kotlinx-json) untuk (de)serialisasi DTO
    // request/response Gemini API, Tahap 3. Versi harus sama persis dengan versi
    // Kotlin 2.2.21 di atas (kotlin.plugin.serialization selalu dirilis serentak
    // dengan versi compiler Kotlin yang bersangkutan).
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
}

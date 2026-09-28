import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.kaneki.aicoder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kaneki.aicoder"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "1.4.0"
        // ABI dibatasi lewat splits.abi di bawah (jangan set ndk.abiFilters
        // bersamaan — AGP akan error "Conflicting configuration").
    }

    /**
     * Split APK per ABI:
     * - app-armeabi-v7a-release.apk  (32-bit ARM)
     * - app-arm64-v8a-release.apk    (64-bit ARM, mayoritas perangkat modern)
     * universalApk=false → tidak buat APK gabungan (ukuran lebih kecil).
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            // Prioritas: environment (GitHub Actions) → keystore/keystore.properties (lokal)
            val envStoreFile = System.getenv("RELEASE_STORE_FILE")
            val envStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
            val envKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
            val envKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")

            if (!envStoreFile.isNullOrBlank()
                && !envStorePassword.isNullOrBlank()
                && !envKeyAlias.isNullOrBlank()
                && !envKeyPassword.isNullOrBlank()
            ) {
                storeFile = file(envStoreFile)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            } else {
                val propsFile = rootProject.file("keystore/keystore.properties")
                if (propsFile.exists()) {
                    val props = Properties()
                    FileInputStream(propsFile).use { props.load(it) }
                    storeFile = rootProject.file("keystore/${props.getProperty("storeFile")}")
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Kalau keystore release tidak tersedia (mis. build lokal tanpa keystore/),
            // jatuh ke debug key supaya assembleRelease tidak gagal dengan storeFile null.
            // Build CI resmi selalu mengisi env RELEASE_*, jadi tetap ditandatangani key release.
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning
            else signingConfigs.getByName("debug")
            // Hapus debug meta agar APK lebih ramping
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            isMinifyEnabled = false
            // Debug tetap penuh untuk development cepat
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "DebugProbesKt.bin"
            )
        }
        jniLibs {
            // Uncompressed native libs → instal lebih cepat, APK sedikit lebih besar di disk
            // tapi download Play dioptimasi; keep default useLegacyPackaging = false
            useLegacyPackaging = false
            // libandroidx.graphics.path.so (dari Compose) sudah tanpa simbol debug, jadi AGP
            // tidak bisa men-strip-nya dan hanya mencetak "Unable to strip the following
            // libraries, packaging them as they are". Hasil akhirnya identik (dikemas apa
            // adanya); menandainya di sini cuma menghilangkan pesan itu, ukuran APK tidak berubah.
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    // Modifier.selectable / selectableGroup (ModelPickerSheet.kt) ada di foundation
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room — 2.8.5 kompatibel KSP2 (Kotlin 2.2.21); 2.6.1 crash "unexpected jvm signature V"
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Coroutines — selaraskan dengan versi transitif Ktor 3.5.2
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // EncryptedSharedPreferences untuk API key (belum ada stable 1.1.x final)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Ktor Client + kotlinx-serialization (Gemini / OpenAI-compat / Cloudflare)
    implementation("io.ktor:ktor-client-android:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Myers diff untuk Diff Review Screen
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
}

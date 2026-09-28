package com.kaneki.aicoder.domain.android

import com.kaneki.aicoder.domain.model.FileTree
import java.io.File

/**
 * Deteksi & ringkasan project Android di dalam ZIP yang di-import.
 * Dipakai untuk:
 * - validasi struktur (manifest, gradle, res, …)
 * - menemukan path kunci (strings.xml app_name, AndroidManifest, …)
 * - memperkaya system prompt agar model langsung tahu file mana yang diedit
 */
object AndroidProjectAnalyzer {

    data class Analysis(
        val isAndroidProject: Boolean,
        /** Skor 0–100 seberapa lengkap strukturnya. */
        val completenessScore: Int,
        val issues: List<String>,
        val hints: List<String>,
        val keyPaths: KeyPaths,
        /** Ringkas untuk disisipkan ke system prompt / ringkasan file tree. */
        val summaryBlock: String
    )

    data class KeyPaths(
        val manifest: String? = null,
        val stringsXml: List<String> = emptyList(),
        val buildGradle: List<String> = emptyList(),
        val settingsGradle: String? = null,
        val mainActivity: List<String> = emptyList(),
        val applicationIdHint: String? = null
    )

    /**
     * Analisis berbasis path saja (cepat, tanpa baca disk).
     * Cukup untuk deteksi struktur & path kunci.
     */
    fun analyzePaths(tree: FileTree): Analysis {
        val paths = tree.files.map { it.path.replace('\\', '/') }
        return analyzePathList(paths)
    }

    /**
     * Analisis + baca app_name dari strings.xml di disk (jika [projectDir] ada).
     */
    fun analyze(tree: FileTree, projectDir: File?): Analysis {
        val base = analyzePaths(tree)
        if (!base.isAndroidProject || projectDir == null) return base

        val appNames = mutableListOf<String>()
        for (stringsPath in base.keyPaths.stringsXml) {
            val file = File(projectDir, stringsPath)
            if (!file.isFile) continue
            val name = extractAppNameFromStringsXml(file.readText())
            if (name != null) appNames.add("$stringsPath → \"$name\"")
        }
        val manifestLabel = base.keyPaths.manifest?.let { mp ->
            val f = File(projectDir, mp)
            if (f.isFile) extractLabelFromManifest(f.readText()) else null
        }

        val extraHints = buildList {
            if (appNames.isNotEmpty()) {
                add("app_name saat ini: ${appNames.joinToString("; ")}")
            }
            if (manifestLabel != null) {
                add("android:label di Manifest: $manifestLabel")
            }
            add(
                "Untuk ganti judul/nama aplikasi di menu launcher: " +
                    "edit resource app_name di strings.xml (atau string yang dirujuk android:label). " +
                    "Jangan ubah file biner (.png/.so/.apk)."
            )
        }

        val block = buildString {
            append(base.summaryBlock)
            if (extraHints.isNotEmpty()) {
                append("\n\nMETADATA ANDROID (dibaca dari disk):\n")
                extraHints.forEach { append("- $it\n") }
            }
        }

        return base.copy(
            hints = base.hints + extraHints,
            summaryBlock = block.trimEnd()
        )
    }

    private fun analyzePathList(paths: List<String>): Analysis {
        val normalized = paths.map { it.trim('/').replace('\\', '/') }

        val manifest = normalized.firstOrNull {
            it.endsWith("AndroidManifest.xml", ignoreCase = true)
        }
        val stringsXml = normalized.filter {
            it.endsWith("/strings.xml", ignoreCase = true) ||
                it.equals("strings.xml", ignoreCase = true) ||
                it.endsWith("/values/strings.xml", ignoreCase = true)
        }.distinct()
        val buildGradle = normalized.filter {
            val n = it.substringAfterLast('/')
            n.equals("build.gradle", ignoreCase = true) ||
                n.equals("build.gradle.kts", ignoreCase = true)
        }
        val settingsGradle = normalized.firstOrNull {
            val n = it.substringAfterLast('/')
            n.equals("settings.gradle", ignoreCase = true) ||
                n.equals("settings.gradle.kts", ignoreCase = true)
        }
        val mainActivity = normalized.filter { p ->
            val n = p.substringAfterLast('/')
            n.contains("MainActivity", ignoreCase = true) &&
                (n.endsWith(".kt") || n.endsWith(".java"))
        }
        val hasRes = normalized.any { it.contains("/res/") || it.startsWith("res/") }
        val hasJavaOrKt = normalized.any {
            it.endsWith(".kt") || it.endsWith(".java")
        }
        val hasGradleWrapper = normalized.any {
            it.contains("gradlew") || it.contains("gradle-wrapper")
        }

        val signals = listOf(
            manifest != null,
            stringsXml.isNotEmpty(),
            buildGradle.isNotEmpty(),
            settingsGradle != null,
            hasRes,
            hasJavaOrKt
        )
        val signalCount = signals.count { it }
        val isAndroid = signalCount >= 2 && (manifest != null || buildGradle.isNotEmpty())

        val issues = mutableListOf<String>()
        val hints = mutableListOf<String>()

        if (isAndroid) {
            if (manifest == null) issues.add("AndroidManifest.xml tidak ditemukan")
            if (stringsXml.isEmpty()) {
                issues.add("strings.xml tidak ditemukan — ganti judul app mungkin lewat Manifest saja")
            }
            if (buildGradle.isEmpty()) issues.add("build.gradle / build.gradle.kts tidak ditemukan")
            if (!hasRes) issues.add("folder res/ tidak terdeteksi")
            if (!hasJavaOrKt) issues.add("tidak ada file .kt/.java")
            if (!hasGradleWrapper) {
                hints.add("gradle wrapper tidak ada di ZIP — user perlu generate wrapper saat build")
            }
            if (stringsXml.isNotEmpty()) {
                hints.add(
                    "Path strings.xml untuk app_name: ${stringsXml.joinToString(", ")}"
                )
            }
            if (manifest != null) {
                hints.add("Path Manifest: $manifest")
            }
        }

        val score = when {
            !isAndroid -> 0
            else -> ((signalCount / 6.0) * 100).toInt().coerceIn(0, 100)
        }

        val keyPaths = KeyPaths(
            manifest = manifest,
            stringsXml = stringsXml,
            buildGradle = buildGradle,
            settingsGradle = settingsGradle,
            mainActivity = mainActivity
        )

        val summaryBlock = if (!isAndroid) {
            ""
        } else {
            buildString {
                appendLine("JENIS PROJECT: Android (skor struktur $score/100)")
                if (issues.isNotEmpty()) {
                    appendLine("PERINGATAN STRUKTUR:")
                    issues.forEach { appendLine("- $it") }
                }
                appendLine("PATH KUNCI:")
                manifest?.let { appendLine("- Manifest: $it") }
                if (stringsXml.isNotEmpty()) appendLine("- strings.xml: ${stringsXml.joinToString(", ")}")
                if (buildGradle.isNotEmpty()) appendLine("- Gradle: ${buildGradle.joinToString(", ")}")
                settingsGradle?.let { appendLine("- settings: $it") }
                if (mainActivity.isNotEmpty()) appendLine("- Activity: ${mainActivity.joinToString(", ")}")
                appendLine("PANDUAN EDIT JUDUL / MENU APP:")
                appendLine("- Prioritas: ubah <string name=\"app_name\">…</string> di strings.xml")
                appendLine("- Pastikan android:label di Manifest merujuk @string/app_name (jangan hardcode kecuali perlu)")
                appendLine("- Setelah edit, export ZIP lalu build di Android Studio / CI")
            }.trimEnd()
        }

        return Analysis(
            isAndroidProject = isAndroid,
            completenessScore = score,
            issues = issues,
            hints = hints,
            keyPaths = keyPaths,
            summaryBlock = summaryBlock
        )
    }

    /** Ambil nilai app_name dari isi strings.xml. */
    fun extractAppNameFromStringsXml(xml: String): String? {
        // <string name="app_name">Judul</string> (dengan atau tanpa atribut lain)
        val regex = Regex(
            """<string\s+[^>]*name\s*=\s*["']app_name["'][^>]*>([^<]*)</string>""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Ambil android:label dari application/activity di Manifest. */
    fun extractLabelFromManifest(xml: String): String? {
        val appLabel = Regex(
            """<application[^>]*android:label\s*=\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(xml)?.groupValues?.getOrNull(1)
        if (appLabel != null) return appLabel.trim()
        return Regex(
            """android:label\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(xml)?.groupValues?.getOrNull(1)?.trim()
    }
}

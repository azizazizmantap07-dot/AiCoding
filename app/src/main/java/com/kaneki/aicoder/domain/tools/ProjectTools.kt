package com.kaneki.aicoder.domain.tools

import com.kaneki.aicoder.data.remote.dto.FunctionTool
import com.kaneki.aicoder.data.remote.dto.ToolParametersSchema
import com.kaneki.aicoder.data.remote.dto.ToolPropertySchema

/**
 * Definisi 4 tools project sesuai Bagian 3.1 blueprint, dalam format
 * Interactions API (`type: "function"` + name/description/parameters).
 *
 * Dipisah dari executor supaya GeminiApiClient hanya tahu deklarasi,
 * bukan implementasi file I/O.
 */
object ProjectTools {

    val declarations: List<FunctionTool> = listOf(
        FunctionTool(
            type = "function",
            name = "list_directory",
            description = "Lihat daftar file dan folder di path tertentu. " +
                "Gunakan di awal untuk memahami struktur sebelum membuka file. " +
                "Path kosong \"\" atau \".\" = root project.",
            parameters = ToolParametersSchema(
                type = "object",
                properties = mapOf(
                    "path" to ToolPropertySchema(
                        type = "string",
                        description = "Path folder relatif dari root project. Kosong = root."
                    )
                ),
                required = listOf("path")
            )
        ),
        FunctionTool(
            type = "function",
            name = "read_file",
            description = "Baca isi file teks. Untuk file besar (>200 baris), WAJIB gunakan " +
                "start_line dan end_line untuk membaca sebagian saja — jangan baca seluruh " +
                "file sekaligus. File biner tidak bisa dibaca.",
            parameters = ToolParametersSchema(
                type = "object",
                properties = mapOf(
                    "path" to ToolPropertySchema(
                        type = "string",
                        description = "Path file relatif dari root project"
                    ),
                    "start_line" to ToolPropertySchema(
                        type = "integer",
                        description = "Baris awal (1-indexed), opsional"
                    ),
                    "end_line" to ToolPropertySchema(
                        type = "integer",
                        description = "Baris akhir (1-indexed, inklusif), opsional"
                    )
                ),
                required = listOf("path")
            )
        ),
        FunctionTool(
            type = "function",
            name = "search_code",
            description = "Cari teks/pola di seluruh project (case-insensitive). " +
                "Gunakan untuk menemukan lokasi kode tanpa membuka semua file satu-satu. " +
                "Hasil dibatasi 50 match.",
            parameters = ToolParametersSchema(
                type = "object",
                properties = mapOf(
                    "query" to ToolPropertySchema(
                        type = "string",
                        description = "Teks yang dicari di isi file"
                    )
                ),
                required = listOf("query")
            )
        ),
        FunctionTool(
            type = "function",
            name = "write_file",
            description = "Tulis/timpa isi file dengan konten baru LENGKAP (bukan potongan). " +
                "Perubahan masuk staging area — belum permanen sampai user review & approve. " +
                "WAJIB baca file dulu dengan read_file sebelum mengedit.",
            parameters = ToolParametersSchema(
                type = "object",
                properties = mapOf(
                    "path" to ToolPropertySchema(
                        type = "string",
                        description = "Path file relatif dari root project"
                    ),
                    "content" to ToolPropertySchema(
                        type = "string",
                        description = "Seluruh isi file setelah perubahan"
                    )
                ),
                required = listOf("path", "content")
            )
        ),
        FunctionTool(
            type = "function",
            name = "rename_file",
            description = "Ganti nama atau pindahkan file ke path lain TANPA mengubah isinya. " +
                "Gunakan ini setiap kali kamu ingin mengganti nama/lokasi file — JANGAN " +
                "pakai write_file dengan path baru untuk menyalin isi file lama. " +
                "Perubahan masuk staging area (belum permanen sampai user review & approve), " +
                "sama seperti write_file. Setelah rename, kalau isi file juga perlu diedit, " +
                "panggil write_file dengan path BARU pada giliran berikutnya.",
            parameters = ToolParametersSchema(
                type = "object",
                properties = mapOf(
                    "old_path" to ToolPropertySchema(
                        type = "string",
                        description = "Path file saat ini (harus sudah ada di project atau di staging)"
                    ),
                    "new_path" to ToolPropertySchema(
                        type = "string",
                        description = "Path baru yang diinginkan (belum boleh dipakai file lain)"
                    )
                ),
                required = listOf("old_path", "new_path")
            )
        )
    )

    /**
     * System prompt wajib (Bagian 3.2 blueprint).
     * [fileTreeSummary] = daftar path awal yang dikirim sekali di awal sesi.
     * [androidBlock] = ringkasan deteksi project Android (opsional, kosong jika bukan Android).
     */
    fun systemPrompt(fileTreeSummary: String, androidBlock: String = ""): String = """
Kamu adalah asisten coding yang bekerja di dalam sebuah project kode sumber.
Kamu memiliki tools untuk melihat struktur project, membaca file, mencari kode,
dan menulis perubahan.

STRUKTUR PROJECT (ringkasan path):
$fileTreeSummary
${if (androidBlock.isNotBlank()) "\n$androidBlock\n" else ""}
ATURAN WAJIB:
1. Mulai dengan list_directory pada root ("") jika belum tahu struktur project.
2. WAJIB baca isi file dengan read_file sebelum mengeditnya — jangan menebak isi file.
3. Untuk file besar, gunakan search_code dulu untuk menemukan baris yang relevan,
   lalu read_file dengan start_line/end_line, jangan baca seluruh file sekaligus.
4. write_file harus berisi SELURUH isi file setelah perubahan, bukan potongan.
5. Jangan mengedit file yang tidak diminta atau tidak relevan.
6. Saat mengedit file yang SUDAH ADA, path pada write_file HARUS SAMA PERSIS
   dengan path file tersebut (case-sensitive, tanpa suffix apa pun).
   DILARANG membuat file baru seperti "nama_fixed.txt", "nama_v2.kt", atau
   "nama_edited.kt" sebagai cara menyimpan hasil edit — itu akan membuat file
   duplikat di project, BUKAN menimpa file lama. Gunakan path baru HANYA jika
   user secara eksplisit meminta file baru dibuat.
6b. Kalau user minta file/folder DIGANTI NAMA atau DIPINDAH (tanpa mengubah
   isinya), gunakan tool rename_file — JANGAN write_file dengan path baru
   berisi salinan konten lama. rename_file menghapus file lama secara benar
   di project, write_file dengan path baru TIDAK menghapus apa pun (hanya
   menambah file baru, meninggalkan file lama tetap ada sebagai duplikat).
   Kalau isi file juga perlu diubah, panggil rename_file dulu, lalu di
   giliran berikutnya write_file ke path yang baru.
7. Jika task kompleks dan butuh banyak file, kerjakan bertahap dan beri tahu user
   progress-nya, jangan mencoba menyelesaikan semua sekaligus dalam satu giliran.
8. Akhiri dengan ringkasan singkat: file apa yang diubah dan kenapa.

ATURAN KHUSUS ANDROID (jika project Android terdeteksi):
- Ganti judul/nama di menu launcher → edit app_name di strings.xml (path ada di blok di atas).
- Jangan mengedit file biner (.png, .webp, .so, .apk, .jar, .aab).
- Setelah write_file, ingatkan user untuk Apply perubahan lalu Export ZIP agar siap di-build.
- Jangan mengarang path: selalu list_directory / search_code / read_file dulu.
""".trimIndent()

    /**
     * System prompt untuk mode chat bebas (belum ada project di-import).
     * Tidak ada tools file — model hanya menjawab percakapan biasa.
     */
    fun freeChatSystemPrompt(): String = """
Kamu adalah asisten coding yang ramah di aplikasi AI Coder.
Saat ini user BELUM mengimpor project atau file apa pun.

ATURAN:
1. Jawab pertanyaan secara umum (penjelasan kode, tips, dll.).
2. JANGAN mencoba melihat folder, membaca file, atau memanggil tool apa pun —
   belum ada project yang tersedia.
3. Jika user ingin mengedit/menganalisis file atau project, arahkan mereka untuk
   menekan tombol Import (ikon folder) atau membuka menu ☰ lalu pilih Import Project.
4. Tetap ringkas dan membantu.
""".trimIndent()
}

package com.kaneki.aicoder.domain.model

/**
 * Representasi satu file dalam project hasil extract ZIP.
 * [path] selalu relatif terhadap root project (mis. "app/src/main/AndroidManifest.xml"),
 * memakai separator '/' apa pun OS-nya, supaya konsisten dipakai sebagai key di
 * berbagai tempat (pendingChanges, tool call args, dsb pada tahap berikutnya).
 */
data class ProjectFile(
    val path: String,
    val sizeBytes: Long,
    val isBinary: Boolean
)

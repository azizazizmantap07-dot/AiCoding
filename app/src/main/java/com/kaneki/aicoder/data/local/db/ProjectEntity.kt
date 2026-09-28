package com.kaneki.aicoder.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Skema sesuai Bagian 7 blueprint. Ditambah [totalSizeBytes] (tidak disebut eksplisit
 * di blueprint tapi berguna untuk badge "ukuran total" di Project Overview Screen,
 * Bagian 6.2 — daripada dihitung ulang tiap kali dari file system).
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val extractedPath: String,
    val createdAt: Long,
    val fileCount: Int,
    val totalSizeBytes: Long
)

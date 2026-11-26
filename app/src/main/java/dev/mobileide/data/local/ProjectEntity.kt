package dev.mobileide.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "projects",
    indices = [Index(value = ["path"], unique = true)]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: String,
    /** Absolute path on disk. Null for rows migrated from the SAF era. */
    val path: String?,
    /** Old content:// URI, kept only to explain why a row needs relocation. */
    val legacyUri: String?,
    val locationName: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val updatedAt: Long
)

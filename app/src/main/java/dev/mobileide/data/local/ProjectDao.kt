package dev.mobileide.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC, id DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    suspend fun findById(projectId: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE path = :path LIMIT 1")
    suspend fun findByPath(path: String): ProjectEntity?

    @Query("SELECT * FROM projects")
    suspend fun getAll(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity): Long

    @Query("UPDATE projects SET name = :name, path = :path, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun updateNameAndPath(
        projectId: Long,
        name: String,
        path: String,
        updatedAt: Long
    )

    @Query(
        "UPDATE projects SET path = :path, locationName = :locationName, " +
            "legacyUri = NULL, updatedAt = :updatedAt WHERE id = :projectId"
    )
    suspend fun updatePath(
        projectId: Long,
        path: String,
        locationName: String,
        updatedAt: Long
    )

    @Query("UPDATE projects SET lastOpenedAt = :openedAt, updatedAt = :openedAt WHERE id = :projectId")
    suspend fun touch(projectId: Long, openedAt: Long)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteById(projectId: Long)
}

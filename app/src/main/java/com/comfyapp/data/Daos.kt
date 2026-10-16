package com.comfyapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE `key` = :key")
    suspend fun get(key: String): WorkflowEntity?

    @Upsert
    suspend fun upsert(e: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("UPDATE workflows SET displayName = :displayName WHERE `key` = :key")
    suspend fun rename(key: String, displayName: String)

    @Query("UPDATE workflows SET paramDefaults = :json WHERE `key` = :key")
    suspend fun saveDefaults(key: String, json: String)
}

@Dao
interface PendingRunDao {
    @Query("SELECT * FROM pending_runs WHERE workflowKey = :key ORDER BY startedAt")
    suspend fun forWorkflow(key: String): List<PendingRun>

    @Upsert
    suspend fun insert(run: PendingRun)

    @Query("DELETE FROM pending_runs WHERE promptId = :pid")
    suspend fun delete(pid: String)
}

@Dao
abstract class ExposeDao {
    @Query("SELECT * FROM exposed_widgets WHERE workflowKey = :key ORDER BY sortOrder, id")
    abstract fun observe(key: String): Flow<List<ExposedWidget>>

    @Query("DELETE FROM exposed_widgets WHERE workflowKey = :key")
    abstract suspend fun deleteFor(key: String)

    @Insert
    abstract suspend fun insertAll(items: List<ExposedWidget>)

    @Query("SELECT * FROM exposed_widgets WHERE workflowKey = :key ORDER BY sortOrder, id")
    abstract suspend fun listFor(key: String): List<ExposedWidget>

    @Transaction
    open suspend fun replaceFor(key: String, items: List<ExposedWidget>) {
        deleteFor(key)
        insertAll(items)
    }
}

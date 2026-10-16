package com.comfyapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.comfyapp.api.ComfyApi
import com.comfyapp.api.ResultImage
import kotlinx.coroutines.flow.Flow
import java.io.File

/** One cached generation result. Files live under filesDir/results/. */
@Entity(tableName = "results")
data class ResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val promptId: String,
    val workflowKey: String? = null,
    val workflowName: String,
    /** Local file name (inside the results dir). */
    val file: String,
    /** Local cached input image for i2i compare; null for txt2img. */
    val inputRef: String? = null,
    /** JSON snapshot of the run's widget overrides (includes seed). */
    val params: String = "{}",
    val durationMs: Long = 0,
    val createdAt: Long,
    val viewedAt: Long = createdAt,
) {
    val isI2i: Boolean get() = inputRef != null
}

@Dao
interface ResultDao {
    @Query("SELECT * FROM results ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ResultEntity>>

    @Query("SELECT * FROM results WHERE id = :id")
    suspend fun get(id: Long): ResultEntity?

    @Insert
    suspend fun insertAll(items: List<ResultEntity>): List<Long>

    @Query("UPDATE results SET viewedAt = :t WHERE id = :id")
    suspend fun touch(id: Long, t: Long)

    @Query("DELETE FROM results WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM results ORDER BY viewedAt ASC")
    suspend fun allByViewedAsc(): List<ResultEntity>

    @Query("SELECT DISTINCT inputRef FROM results WHERE inputRef IS NOT NULL")
    suspend fun remainingInputRefs(): List<String>
}

/**
 * Downloads run outputs (and the input image for i2i) into app-private storage and
 * tracks them in Room. Server /history is volatile — this cache is the results
 * screen's source of truth. LRU-trims to a byte budget.
 */
class ResultsRepository(
    private val dao: ResultDao,
    private val api: ComfyApi,
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_BUDGET: Long = 500L * 1024 * 1024
    }

    fun observe(): Flow<List<ResultEntity>> = dao.observeAll()

    suspend fun get(id: Long): ResultEntity? = dao.get(id)

    fun fileOf(e: ResultEntity): File = File(dir, e.file)

    fun inputFileOf(e: ResultEntity): File? = e.inputRef?.let { File(dir, it) }

    /** Run-completion hook: persist all outputs now, trim after. Returns inserted row ids. */
    suspend fun capture(
        promptId: String,
        workflowKey: String?,
        workflowName: String,
        images: List<ResultImage>,
        inputImage: ResultImage?,
        paramsJson: String,
        durationMs: Long,
    ): List<Long> = runCatching {
        dir.mkdirs()
        val t = now()
        var inputRef: String? = null
        if (inputImage != null) {
            val bytes = runCatching { api.download(inputImage) }.getOrNull()
            if (bytes != null) {
                val name = "${t}-in-${inputImage.filename.replace(Regex("[\\\\/]"), "_")}"
                File(dir, name).writeBytes(bytes)
                inputRef = name
            }
        }
        val entities = images.mapIndexed { i, img ->
            val bytes = runCatching { api.download(img) }.getOrElse { ByteArray(0) }
            val name = "${t}-$i-${img.filename.replace(Regex("[\\\\/]"), "_")}"
            File(dir, name).writeBytes(bytes)
            ResultEntity(
                promptId = promptId,
                workflowKey = workflowKey,
                workflowName = workflowName,
                file = name,
                inputRef = inputRef,
                params = paramsJson,
                durationMs = durationMs,
                createdAt = t + i,
            )
        }.filter { File(dir, it.file).length() > 0 }
        val ids = dao.insertAll(entities)
        trim()
        ids
    }.getOrDefault(emptyList())

    suspend fun delete(ids: List<Long>) {
        val entities = ids.mapNotNull { dao.get(it) }
        for (e in entities) fileOf(e).delete()
        dao.deleteByIds(ids)
        // input images are shared by all rows of one run — remove once unreferenced
        val remaining = dao.remainingInputRefs().toSet()
        for (e in entities) {
            val ref = e.inputRef ?: continue
            if (ref !in remaining) File(dir, ref).delete()
        }
    }

    suspend fun touchViewed(id: Long) = dao.touch(id, now())

    /** Cache size in bytes (files only). */
    suspend fun cacheSizeBytes(): Long =
        dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Delete least-recently-viewed results until total files fit [budgetBytes]. */
    suspend fun trim(budgetBytes: Long = DEFAULT_BUDGET) {
        val files = dir.listFiles()?.associateBy { it.name } ?: return
        var total = files.values.sumOf { it.length() }
        if (total <= budgetBytes) return
        for (e in dao.allByViewedAsc()) {
            if (total <= budgetBytes) break
            val f = files[e.file] ?: continue
            total -= f.length()
            f.delete()
            dao.deleteByIds(listOf(e.id))
        }
        // drop input files that lost all referencing rows
        val remaining = dao.remainingInputRefs().toSet()
        for (f in files.values) {
            val isInput = f.name.contains("-in-")
            if (isInput && f.exists() && f.name !in remaining) f.delete()
        }
    }
}

package com.comfyapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val key: String,
    val name: String,
    /** 本地显示名；null = 沿用 [name]。 */
    val displayName: String? = null,
    /** Raw UI-format workflow JSON. */
    val json: String,
    val updatedAt: Long,
    /** 表单默认参数快照（JSON map "nodeId:widgetName" → value）；null = 未保存过。 */
    val paramDefaults: String? = null,
)

@Entity(tableName = "exposed_widgets")
data class ExposedWidget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workflowKey: String,
    /** Node id as string; subgraph instance widgets use the instance node id. */
    val nodeId: String,
    val widgetName: String,
    /** User-facing label override; empty = use widget name. */
    val label: String = "",
    val sortOrder: Int = 0,
)

/** 已提交服务器但未收尾的运行：进程被杀/切后台后凭服务器 history 恢复。 */
@Entity(tableName = "pending_runs")
data class PendingRun(
    @PrimaryKey val promptId: String,
    val workflowKey: String,
    val workflowName: String,
    val startedAt: Long,
)

/** Stable workflow identity: content hash. Re-import of same content updates in place. */
fun workflowKey(json: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

interface WorkflowRepository {
    fun list(): Flow<List<WorkflowEntity>>
    suspend fun get(key: String): WorkflowEntity?

    /** Insert or update by content key. Returns the key. */
    suspend fun import(name: String, json: String): String

    suspend fun updateJson(key: String, json: String): Unit
    /** 重命名（本地显示名）。 */
    suspend fun rename(key: String, displayName: String): Unit
    /** 复制工作流（含暴露配置与参数默认值）到新 key；显示名加“ 副本”。返回新 key。 */
    suspend fun duplicate(key: String): String?
    /** 保存表单参数快照，作为下次打开的默认值。 */
    suspend fun saveDefaults(key: String, paramsJson: String)
    suspend fun delete(key: String)
}

interface PendingRunRepository {
    suspend fun forWorkflow(key: String): List<PendingRun>
    suspend fun insert(run: PendingRun)
    suspend fun delete(promptId: String)
}

interface ExposeRepository {
    fun exposed(workflowKey: String): Flow<List<ExposedWidget>>

    /** Replace the whole exposure list for a workflow (single transaction). */
    suspend fun replace(workflowKey: String, widgets: List<ExposedWidget>)
}

interface SettingsRepository {
    /** Last server URL, e.g. "http://192.168.1.10:8188". Null until first setup. */
    val serverUrl: Flow<String?>
    suspend fun setServerUrl(url: String)

    /** Up to 5 most recently connected addresses, newest first. */
    val recentUrls: Flow<List<String>>
    suspend fun rememberUrl(url: String)
}

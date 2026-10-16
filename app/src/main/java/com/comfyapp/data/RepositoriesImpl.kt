package com.comfyapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class WorkflowRepositoryImpl(
    private val dao: WorkflowDao,
    private val exposeDao: ExposeDao,
    private val json: Json,
) : WorkflowRepository {
    override fun list(): Flow<List<WorkflowEntity>> = dao.observeAll()

    override suspend fun get(key: String): WorkflowEntity? = dao.get(key)

    override suspend fun import(name: String, json: String): String {
        val key = workflowKey(json)
        // 重复导入：保留本地显示名与勾选状态（行替换会清 displayName）
        val existing = dao.get(key)
        dao.upsert(
            WorkflowEntity(
                key = key, name = name,
                displayName = existing?.displayName,
                json = json, updatedAt = System.currentTimeMillis(),
            ),
        )
        return key
    }

    override suspend fun updateJson(key: String, json: String) {
        val existing = dao.get(key) ?: return
        dao.upsert(existing.copy(json = json, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun rename(key: String, displayName: String) = dao.rename(key, displayName)

    override suspend fun duplicate(key: String): String? {
        val src = dao.get(key) ?: return null
        // 衍生 key 带随机后缀：脱离 content-hash 空间，后续编辑保存不会覆盖原工作流
        var newKey: String
        do {
            newKey = key + "-" + List(4) { "0123456789abcdef".random() }.joinToString("")
        } while (dao.get(newKey) != null)
        dao.upsert(
            src.copy(
                key = newKey,
                displayName = (src.displayName ?: src.name) + " 副本",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        exposeDao.insertAll(exposeDao.listFor(key).map { it.copy(id = 0, workflowKey = newKey) })
        return newKey
    }

    override suspend fun saveDefaults(key: String, paramsJson: String) = dao.saveDefaults(key, paramsJson)
    override suspend fun delete(key: String) = dao.delete(key)
}

class PendingRunRepositoryImpl(private val dao: PendingRunDao) : PendingRunRepository {
    override suspend fun forWorkflow(key: String): List<PendingRun> = dao.forWorkflow(key)
    override suspend fun insert(run: PendingRun) = dao.insert(run)
    override suspend fun delete(promptId: String) = dao.delete(promptId)
}

class ExposeRepositoryImpl(private val dao: ExposeDao) : ExposeRepository {
    override fun exposed(workflowKey: String): Flow<List<ExposedWidget>> = dao.observe(workflowKey)

    override suspend fun replace(workflowKey: String, widgets: List<ExposedWidget>) {
        dao.replaceFor(workflowKey, widgets.map { it.copy(id = 0, workflowKey = workflowKey) })
    }
}

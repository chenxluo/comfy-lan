package com.comfyapp

import android.app.Application
import androidx.room.Room
import com.comfyapp.api.ComfyApi
import com.comfyapp.api.ComfyEvents
import com.comfyapp.api.OkHttpComfyApi
import com.comfyapp.api.OkHttpComfyEvents
import com.comfyapp.convert.DefaultPromptConverter
import com.comfyapp.convert.PromptConverter
import com.comfyapp.data.AppDatabase
import com.comfyapp.data.ExposeRepository
import com.comfyapp.data.ResultsRepository
import com.comfyapp.data.ExposeRepositoryImpl
import com.comfyapp.data.SettingsRepository
import com.comfyapp.data.SettingsRepositoryImpl
import com.comfyapp.data.WorkflowRepository
import com.comfyapp.data.WorkflowRepositoryImpl
import com.comfyapp.data.PendingRunRepository
import com.comfyapp.data.PendingRunRepositoryImpl
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class ComfyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual DI. One instance per process, created in [ComfyApp]. */
class AppContainer(app: ComfyApp) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settings: SettingsRepository = SettingsRepositoryImpl(app)

    private val db: AppDatabase =
        Room.databaseBuilder(app, AppDatabase::class.java, "comfy.db")
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            // v1→v2 加 results 表时的破坏性迁移兜底（v2 起走显式迁移）
            .fallbackToDestructiveMigration()
            .build()

    val workflows: WorkflowRepository = WorkflowRepositoryImpl(db.workflowDao(), db.exposeDao(), json)

    val exposed: ExposeRepository = ExposeRepositoryImpl(db.exposeDao())

    val pendingRuns: PendingRunRepository = PendingRunRepositoryImpl(db.pendingRunDao())

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // No read timeout: WebSocket and long-poll style endpoints.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val api: ComfyApi = OkHttpComfyApi(okHttp, json)
    val events: ComfyEvents = OkHttpComfyEvents(okHttp, json) { api.baseUrl }
    val converter: PromptConverter = DefaultPromptConverter()

    val results: ResultsRepository =
        ResultsRepository(db.resultDao(), api, File(app.filesDir, "results"))
}

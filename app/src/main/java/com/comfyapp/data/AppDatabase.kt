package com.comfyapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WorkflowEntity::class, ExposedWidget::class, ResultEntity::class, PendingRun::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao
    abstract fun exposeDao(): ExposeDao
    abstract fun resultDao(): ResultDao
    abstract fun pendingRunDao(): PendingRunDao

    companion object {
        /** v2→v3: workflows 加本地显示名列（纯 ALTER，数据保留）。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workflows` ADD COLUMN `displayName` TEXT")
            }
        }

        /** v3→v4: workflows 加参数默认值快照列。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workflows` ADD COLUMN `paramDefaults` TEXT")
            }
        }

        /** v4→v5: pending_runs 表（运行恢复）。 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_runs` (" +
                        "`promptId` TEXT NOT NULL, `workflowKey` TEXT NOT NULL, " +
                        "`workflowName` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`promptId`))",
                )
            }
        }
    }
}

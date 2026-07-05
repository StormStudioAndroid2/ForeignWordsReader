package com.example.myapplication.shared.processing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

class AndroidGlobalFrequencyRepositoryFactory(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun create(): GlobalFrequencyRepository {
        installDatabase()
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(appContext)
                .name(FrequencyDatabaseName)
                .callback(FrequencyDatabaseCallback)
                .build(),
        )
        return SqlDelightGlobalFrequencyRepository(
            driver = AndroidSqliteDriver(openHelper),
        )
    }

    private fun installDatabase(): File {
        val target = appContext.getDatabasePath(FrequencyDatabaseName)
        if (installedDatabaseVersion(target) != ExpectedFrequencyDatabaseVersion) {
            target.parentFile?.mkdirs()
            appContext.assets.open(FrequencyDatabaseAssetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return target
    }

    private fun installedDatabaseVersion(database: File): String? {
        if (!database.exists() || database.length() == 0L) {
            return null
        }

        return runCatching {
            SQLiteDatabase.openDatabase(
                database.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqliteDatabase ->
                sqliteDatabase.rawQuery(
                    "SELECT value FROM metadata WHERE key = ?",
                    arrayOf("database_version"),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val FrequencyDatabaseAssetPath = "frequency/global-frequency.sqlite"
        const val FrequencyDatabaseName = "global-frequency.sqlite"
        const val ExpectedFrequencyDatabaseVersion = "3"

        val FrequencyDatabaseCallback = object : SupportSQLiteOpenHelper.Callback(version = 1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }
    }
}

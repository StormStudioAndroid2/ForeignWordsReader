package com.example.myapplication.shared.processing

import android.content.Context
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
        val assetLength = appContext.assets.open(FrequencyDatabaseAssetPath).use { input ->
            input.available().toLong()
        }
        if (!target.exists() || target.length() != assetLength) {
            target.parentFile?.mkdirs()
            appContext.assets.open(FrequencyDatabaseAssetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return target
    }

    private companion object {
        const val FrequencyDatabaseAssetPath = "frequency/global-frequency.sqlite"
        const val FrequencyDatabaseName = "global-frequency.sqlite"

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

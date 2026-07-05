package com.example.myapplication.shared.processing

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.NO_VERSION_CHECK
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
class IosGlobalFrequencyRepositoryFactory {
    fun create(): GlobalFrequencyRepository {
        val databasePath = installDatabase()
        val driver = createDriver(databasePath)
        return SqlDelightGlobalFrequencyRepository(driver = driver)
    }

    private fun installDatabase(): String {
        val sourcePath = NSBundle.mainBundle.pathForResource(
            name = "global-frequency",
            ofType = "sqlite",
            inDirectory = "frequency",
        ) ?: NSBundle.mainBundle.pathForResource(
            name = "global-frequency",
            ofType = "sqlite",
        ) ?: error("Bundled global frequency database was not found.")

        val targetDirectory = frequencyDirectory()
        val targetPath = "$targetDirectory/global-frequency.sqlite"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = targetDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        if (installedDatabaseVersion(targetPath) != ExpectedFrequencyDatabaseVersion) {
            if (NSFileManager.defaultManager.fileExistsAtPath(targetPath)) {
                NSFileManager.defaultManager.removeItemAtPath(targetPath, null)
            }
            val copied = NSFileManager.defaultManager.copyItemAtPath(
                srcPath = sourcePath,
                toPath = targetPath,
                error = null,
            )
            check(copied) { "Could not install bundled global frequency database." }
        }

        return targetPath
    }

    private fun createDriver(databasePath: String): NativeSqliteDriver {
        val databaseDirectory = databasePath.substringBeforeLast('/')
        val databaseName = databasePath.substringAfterLast('/')
        return NativeSqliteDriver(
            configuration = DatabaseConfiguration(
                name = databaseName,
                version = NO_VERSION_CHECK,
                create = {},
                extendedConfig = DatabaseConfiguration.Extended(basePath = databaseDirectory),
            ),
        )
    }

    private fun installedDatabaseVersion(databasePath: String): String? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(databasePath)) {
            return null
        }

        return runCatching {
            val driver = createDriver(databasePath)
            var version: String? = null
            try {
                driver.executeQuery(
                    identifier = null,
                    sql = "SELECT value FROM metadata WHERE key = ?",
                    mapper = { cursor ->
                        if (cursor.next().value) {
                            version = cursor.getString(0)
                        }
                        QueryResult.Value(Unit)
                    },
                    parameters = 1,
                    binders = {
                        bindString(0, "database_version")
                    },
                ).value
            } finally {
                driver.close()
            }
            version
        }.getOrNull()
    }

    private fun frequencyDirectory(): String {
        val baseDirectory = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: error("Application Support directory is unavailable.")
        return "$baseDirectory/frequency"
    }

    private companion object {
        const val ExpectedFrequencyDatabaseVersion = "3"
    }
}

package com.ehviewer.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual inline fun <reified T : RoomDatabase> roomDb(
    name: String,
    builder: RoomDatabase.Builder<T>.() -> Unit,
) = Room.databaseBuilder<T>(name = name, factory = { BundledSQLiteDriver() }).apply(builder).build()

actual fun getDatabasePath(name: String): Path = appSupportDir() / name

/** iOS 没有 Android 的 filesDir，数据库和偏好设置统一放 Application Support。 */
internal fun appSupportDir(): Path {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val path = requireNotNull(url?.path) { "Application Support directory is unavailable" }
    return path.toPath()
}

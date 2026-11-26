package com.ehviewer.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ehviewer.core.database.appSupportDir
import okio.Path.Companion.toPath

internal actual fun getDateStore(name: String?): DataStore<Preferences> {
    val actualName = name ?: "ehviewer_preferences"
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = { (appSupportDir() / "$actualName.preferences_pb").toString().toPath() },
    )
}

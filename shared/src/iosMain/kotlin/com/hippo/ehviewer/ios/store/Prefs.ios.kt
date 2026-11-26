package com.hippo.ehviewer.ios.store

import platform.Foundation.NSUserDefaults

actual object Prefs {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun getString(key: String): String? = defaults.stringForKey(key)

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, key)
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

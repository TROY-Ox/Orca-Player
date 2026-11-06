package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * The Android plugin base class extensions extend. CloudStream keeps it in its app rather than
 * in the library module, so a host has to provide it with the same name and members.
 * (GPL-3.0, extended build only.)
 */
abstract class Plugin : BasePlugin() {
    /** Called once when the extension is loaded. */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    /** The extension's own resources, when its manifest asks for them. */
    var resources: Resources? = null

    /** Set by extensions that have their own settings screen. */
    var openSettings: ((context: Context) -> Unit)? = null
}

package com.troy.orcaplayer.flavor

import android.app.Application
import androidx.compose.runtime.Composable
import com.troy.orcaplayer.data.AddonRepository

/** The standard build: no CloudStream extensions, no downloaded code (3.1). */
object Flavor {
    const val EXTENSIONS = false

    fun init(app: Application, addons: AddonRepository) = Unit

    @Composable
    fun ExtensionsScreen(onBack: () -> Unit) = Unit
}

package com.troy.orcaplayer

import android.app.Application
import com.troy.orcaplayer.core.data.ProfileRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OrcaApp : Application() {
    @Inject lateinit var profiles: ProfileRepository

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            profiles.ensureDefaultProfile(getString(R.string.profiles_default_name))
        }
    }
}

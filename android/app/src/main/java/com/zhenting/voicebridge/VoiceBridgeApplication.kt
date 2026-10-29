package com.zhenting.voicebridge

import android.app.Application
import com.zhenting.voicebridge.data.AppContainer

class VoiceBridgeApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(BuildConfig.API_BASE_URL)
    }
}

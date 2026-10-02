package com.hy.assistant

import android.app.Application
import com.hy.assistant.auto.ActivityLog
import com.hy.assistant.auto.Automation
import com.hy.assistant.auto.HyNotifications
import com.hy.assistant.llm.LlamaEngine
import com.hy.assistant.models.ModelManager
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.notifications.NotificationFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HyApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: Settings
        private set
    lateinit var models: ModelManager
        private set
    lateinit var engine: LlamaEngine
        private set
    lateinit var automation: Automation
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        MessageStore.init(this, appScope)
        NotificationFeed.init(this, appScope)
        ActivityLog.init(this)
        HyNotifications.createChannels(this)
        models = ModelManager(this, settings, appScope)
        engine = LlamaEngine(appScope)
        automation = Automation(this, settings, models, engine, appScope)
    }

    companion object {
        lateinit var instance: HyApp
            private set
    }
}

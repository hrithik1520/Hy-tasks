package com.hy.assistant

import android.app.Application
import com.hy.assistant.auto.ActivityLog
import com.hy.assistant.auto.Automation
import com.hy.assistant.auto.HyNotifications
import com.hy.assistant.llm.LlamaEngine
import com.hy.assistant.memory.ConversationStore
import com.hy.assistant.memory.MemoryStore
import com.hy.assistant.models.ModelManager
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.notifications.NotificationFeed
import com.hy.assistant.planner.Reminders
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
        MemoryStore.init(this)
        ConversationStore.init(this)
        Reminders.init(this)
        HyNotifications.createChannels(this)
        models = ModelManager(this, settings, appScope)
        engine = LlamaEngine(appScope, this) { settings.current.useGpu }
        if (engine.gpuCrashedLastRun && settings.current.useGpu) {
            // The GPU driver took the app down last time: back to the CPU (the user can test again).
            settings.update { it.copy(useGpu = false) }
            ActivityLog.add(ActivityLog.Kind.FAILED, "GPU", "Switched back to the CPU", "the app stopped while using the GPU")
        }
        automation = Automation(this, settings, models, engine, appScope)
    }

    companion object {
        lateinit var instance: HyApp
            private set
    }
}

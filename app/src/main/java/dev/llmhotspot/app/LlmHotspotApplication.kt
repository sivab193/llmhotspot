package dev.llmhotspot.app

import android.app.Application
import dev.llmhotspot.app.model.ModelRepository

class LlmHotspotApplication : Application() {
    lateinit var models: ModelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        models = ModelRepository(this)
    }
}

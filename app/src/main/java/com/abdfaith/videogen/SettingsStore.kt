package com.abdfaith.videogen

import android.content.Context

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = ProviderConfig(
        spaceUrl = sp.getString("spaceUrl", DEFAULT_URL) ?: DEFAULT_URL,
        apiName = sp.getString("apiName", DEFAULT_API) ?: DEFAULT_API,
        dataTemplate = sp.getString("template", DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE,
        hfToken = sp.getString("token", "") ?: "",
        negativePrompt = sp.getString("negative", DEFAULT_NEGATIVE) ?: DEFAULT_NEGATIVE,
        chainFrames = sp.getBoolean("chain", true),
        parallel = sp.getInt("parallel", 1).coerceIn(1, 4)
    )

    fun save(c: ProviderConfig) {
        sp.edit()
            .putString("spaceUrl", c.spaceUrl.trim())
            .putString("apiName", c.apiName.trim())
            .putString("template", c.dataTemplate)
            .putString("token", c.hfToken.trim())
            .putString("negative", c.negativePrompt)
            .putBoolean("chain", c.chainFrames)
            .putInt("parallel", c.parallel.coerceIn(1, 4))
            .apply()
    }

    companion object {
        // قيم افتراضية قابلة للتعديل: الـ Spaces المجانية تتغير أو تتوقف أو تُقيَّد بحصة GPU.
        const val DEFAULT_URL = "https://kingnish-ltx-video-distilled.hf.space"
        const val DEFAULT_API = "generate"
        const val DEFAULT_TEMPLATE = """["{{prompt}}", {{image}}, null, null, 3]"""
        const val DEFAULT_NEGATIVE = "worst quality, blurry, jittery, distorted"
    }
}

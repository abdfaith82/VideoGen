package com.abdfaith.videogen

enum class SceneStatus { PENDING, RUNNING, DONE, FAILED }

data class Scene(
    val id: String,
    val prompt: String = "",
    val status: SceneStatus = SceneStatus.PENDING,
    val videoPath: String? = null,
    val lastFramePath: String? = null,
    val error: String? = null
)

data class Project(
    val id: String,
    val title: String,
    val startImagePath: String? = null,
    val scenes: List<Scene> = emptyList(),
    val outputPath: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * إعدادات مزوّد التوليد (Gradio Space على Hugging Face).
 * dataTemplate: مصفوفة JSON تطابق مدخلات الـ API في صفحة "Use via API" للـ Space،
 * وتدعم العناصر البديلة: {{prompt}} {{negative}} {{image}} {{seed}}
 */
data class ProviderConfig(
    val spaceUrl: String,
    val apiName: String,
    val dataTemplate: String,
    val hfToken: String,
    val negativePrompt: String,
    val chainFrames: Boolean
)

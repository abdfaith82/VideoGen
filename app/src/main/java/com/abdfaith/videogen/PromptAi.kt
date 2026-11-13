package com.abdfaith.videogen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class AiResult<T>(val value: T, val fromAi: Boolean)

/**
 * مساعد البرومبتات: يستخدم واجهة نصية مجانية متوافقة مع OpenAI (Pollinations) بلا مفتاح،
 * وعند تعذّرها يعمل بقوالب محلية حتى لا تتعطل الميزة.
 */
class PromptAi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    suspend fun enhance(description: String): AiResult<String> {
        val desc = description.trim()
        return try {
            val out = chat(ENHANCE_SYSTEM, desc)
                .trim().trim('"', '`', '\'', ' ', '\n')
            if (out.length < 15) throw IOException("رد قصير")
            AiResult(out, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiResult(localEnhance(desc), false)
        }
    }

    suspend fun split(description: String, count: Int): AiResult<List<String>> {
        val desc = description.trim()
        return try {
            val raw = chat(
                "You are a film director's assistant for AI text-to-video models. " +
                    "Split the user's scene (any language) into exactly $count consecutive shots that tell one " +
                    "continuous story. Each shot is a standalone English video prompt of 30-60 words: subject, " +
                    "action/motion, camera (shot type and movement), lighting, mood. Keep characters, setting and " +
                    "visual style consistent across shots. Output ONLY a JSON array of $count strings, nothing else.",
                desc
            )
            val shots = parseShots(raw)
            if (shots.size < count) throw IOException("عدد اللقطات ${shots.size} أقل من المطلوب")
            AiResult(shots.take(count), true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiResult(localSplit(desc, count), false)
        }
    }

    private fun parseShots(text: String): List<String> {
        val s = text.indexOf('[')
        val e = text.lastIndexOf(']')
        if (s < 0 || e <= s) return emptyList()
        val arr = JSONArray(text.substring(s, e + 1))
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { x -> x.length >= 10 } }
    }

    private suspend fun chat(system: String, user: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", "openai")
            .put("messages", messages)
            .put("seed", Random.nextInt(1, 100000))
        val req = Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }

    private fun localEnhance(desc: String) =
        "$desc, cinematic shot, natural realistic motion, soft volumetric lighting, " +
            "shallow depth of field, rich color grading, photorealistic, highly detailed, 4k"

    private fun localSplit(desc: String, count: Int): List<String> =
        List(count) { i ->
            "$desc, ${BEATS[i % BEATS.size]}, shot ${i + 1} of $count continuing the same scene, " +
                "consistent characters and setting, cinematic lighting, photorealistic, natural motion"
        }

    companion object {
        private const val ENHANCE_SYSTEM =
            "You write prompts for AI text-to-video models (LTX-Video, Wan). Convert the user's scene " +
                "description (any language) into ONE professional English prompt of 50-90 words covering: " +
                "subject, action and motion, setting, camera (shot type and movement), lighting, color and " +
                "mood, realistic cinematic style. Output only the prompt: no quotes, no preface, no lists."

        private val BEATS = listOf(
            "establishing wide shot", "slow push-in", "medium shot", "close-up on details",
            "over-the-shoulder angle", "low-angle hero shot", "tracking shot following the motion",
            "extreme close-up", "high-angle overview", "slow pan across the environment",
            "handheld close follow", "profile side view", "reaction close-up", "wide shot with depth",
            "macro detail shot", "dolly out revealing the surroundings", "rack focus shot",
            "silhouette against the light", "slow-motion moment", "final wide closing shot"
        )
    }
}

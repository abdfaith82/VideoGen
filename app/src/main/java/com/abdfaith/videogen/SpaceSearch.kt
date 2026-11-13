package com.abdfaith.videogen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln

data class SpaceCandidate(
    val id: String,
    val url: String,
    val likes: Int,
    val ep: EndpointInfo,
    val acceptsImage: Boolean,
    val score: Double
)

/**
 * يبحث في فهرس Hugging Face عن Spaces، ثم يفحص كل مرشّح فعلياً (هل يعمل الآن؟ هل له دالة تُرجع فيديو؟
 * هل تقبل نصاً؟) ويرتّب الصالحين حسب الجودة. لا يضمن نجاح التوليد (حصة GPU تبقى خارج السيطرة).
 */
class SpaceSearch {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // فحص سريع: Space نائم أو معطّل يُستبعد خلال ثوانٍ
    private val probe = SpaceInspector(6, 10)

    suspend fun search(
        query: String,
        token: String,
        onProgress: (String) -> Unit
    ): List<SpaceCandidate> {
        val listed = withContext(Dispatchers.IO) { fetchList(query, token) }
        if (listed.isEmpty()) return emptyList()

        val gate = Semaphore(6)
        val done = AtomicInteger(0)
        val results = coroutineScope {
            listed.map { (id, likes) ->
                async {
                    gate.withPermit {
                        val c = check(id, likes, token)
                        onProgress("فحص ${done.incrementAndGet()} من ${listed.size}…")
                        c
                    }
                }
            }.awaitAll()
        }
        return results.filterNotNull().sortedByDescending { it.score }.take(8)
    }

    private suspend fun check(id: String, likes: Int, token: String): SpaceCandidate? {
        val url = SpaceInspector.normalizeUrl(id)
        val eps = try {
            probe.inspect(url, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        fun hasText(e: EndpointInfo) =
            e.params.any { it.component.lowercase() in setOf("textbox", "textarea") }

        val best = eps.filter { it.returnsVideo && hasText(it) }
            .minByOrNull { it.params.size } ?: return null
        val image = best.params.any { it.component.equals("image", ignoreCase = true) }
        val nameBonus = if (Regex("generate|predict|video|t2v|i2v", RegexOption.IGNORE_CASE)
                .containsMatchIn(best.apiName)
        ) 3.0 else 0.0
        val score = ln(1.0 + likes) * 10.0 + (if (image) 8.0 else 0.0) +
            nameBonus - best.params.size * 0.5
        return SpaceCandidate(id, url, likes, best, image, score)
    }

    private fun fetchList(query: String, token: String): List<Pair<String, Int>> {
        fun build(expand: Boolean): Request {
            val b = "https://huggingface.co/api/spaces".toHttpUrl().newBuilder()
                .addQueryParameter("search", query.trim())
                .addQueryParameter("sort", "likes")
                .addQueryParameter("direction", "-1")
                .addQueryParameter("limit", "40")
            if (expand) {
                listOf("likes", "runtime", "sdk").forEach { b.addQueryParameter("expand[]", it) }
            }
            val rb = Request.Builder().url(b.build())
            if (token.isNotBlank()) rb.header("Authorization", "Bearer ${token.trim()}")
            return rb.build()
        }

        for (expand in listOf(true, false)) {
            val text = http.newCall(build(expand)).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            } ?: continue
            val arr = try {
                JSONArray(text)
            } catch (e: JSONException) {
                continue
            }
            val out = mutableListOf<Pair<String, Int>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val sdk = o.optString("sdk")
                if (sdk.isNotEmpty() && sdk != "gradio") continue
                val stage = o.optJSONObject("runtime")?.optString("stage").orEmpty()
                if (stage.isNotEmpty() && stage != "RUNNING") continue
                out += id to o.optInt("likes", 0)
            }
            return out
        }
        throw IOException("تعذّر الوصول إلى فهرس Hugging Face")
    }
}

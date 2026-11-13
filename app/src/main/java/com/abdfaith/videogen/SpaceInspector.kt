package com.abdfaith.videogen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ParamInfo(
    val label: String,
    val name: String,
    val component: String,
    val hasDefault: Boolean,
    val default: Any?
)

data class EndpointInfo(
    val apiName: String,
    val params: List<ParamInfo>,
    val returnsVideo: Boolean
)

/** يقرأ وصف واجهة Gradio (/info) ليملأ api_name وقالب data تلقائياً. */
class SpaceInspector(connectSec: Long = 20, readSec: Long = 40) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .build()

    suspend fun inspect(rawUrl: String, token: String): List<EndpointInfo> =
        withContext(Dispatchers.IO) {
            val base = normalizeUrl(rawUrl)
            if (base.isBlank()) throw IOException("أدخل رابط الـ Space أولاً")
            val unset = "تعذّر قراءة واجهة الـ Space"
            var lastErr = unset

            for (prefix in listOf("/gradio_api", "")) {
                val b = Request.Builder().url("$base$prefix/info")
                if (token.isNotBlank()) b.header("Authorization", "Bearer ${token.trim()}")
                try {
                    val result: List<EndpointInfo>? = http.newCall(b.build()).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        when {
                            r.code == 502 || r.code == 503 -> {
                                lastErr = "الـ Space معطّل أو نائم (${r.code}). جرّب Space آخر."
                                null
                            }
                            r.code == 404 -> {
                                if (lastErr == unset) lastErr = "لم أجد واجهة Gradio في هذا الرابط (404)"
                                null
                            }
                            !r.isSuccessful -> {
                                lastErr = "الخدمة ردّت بالخطأ ${r.code}"
                                null
                            }
                            else -> parse(text)
                        }
                    }
                    if (result != null) return@withContext result
                } catch (e: JSONException) {
                    lastErr = "الرد ليس واجهة Gradio (قد يكون الـ Space نائماً أو الرابط غير صحيح)"
                } catch (e: IOException) {
                    lastErr = "تعذّر الاتصال: ${e.message}"
                }
            }
            throw IOException(lastErr)
        }

    /** يقرأ الـ Space ويبني قالب data للدالة المحددة. يرمي IOException برسالة واضحة عند الفشل. */
    suspend fun templateFor(rawUrl: String, token: String, apiName: String): String {
        val wanted = apiName.trim().trimStart('/')
        if (wanted.isEmpty()) throw IOException("أدخل api_name أولاً")
        val ep = inspect(rawUrl, token).firstOrNull { it.apiName == wanted }
            ?: throw IOException("لم أجد الدالة «$wanted» في هذا الـ Space. اضغط «اختبار الاتصال» لرؤية الدوال المتاحة.")
        return buildTemplate(ep)
    }

    private fun parse(text: String): List<EndpointInfo> {
        val named = JSONObject(text).optJSONObject("named_endpoints") ?: return emptyList()
        val out = mutableListOf<EndpointInfo>()
        val keys = named.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val ep = named.optJSONObject(key) ?: continue
            val ps = ep.optJSONArray("parameters") ?: JSONArray()
            val params = (0 until ps.length()).mapNotNull { ps.optJSONObject(it) }.map { p ->
                ParamInfo(
                    label = p.optString("label"),
                    name = p.optString("parameter_name"),
                    component = p.optString("component"),
                    hasDefault = p.optBoolean("parameter_has_default", false),
                    default = if (p.isNull("parameter_default")) null else p.opt("parameter_default")
                )
            }
            val rs = ep.optJSONArray("returns") ?: JSONArray()
            val video = (0 until rs.length()).any {
                rs.optJSONObject(it)?.optString("component").equals("Video", ignoreCase = true)
            }
            out += EndpointInfo(key.trimStart('/'), params, video)
        }
        return out.sortedByDescending { it.returnsVideo }
    }

    companion object {
        /** يقبل: رابط hf.space، أو رابط صفحة huggingface.co/spaces/owner/name، أو owner/name. */
        fun normalizeUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            val page = Regex("""^(?:https?://)?(?:www\.)?huggingface\.co/spaces/([^/\s?#]+)/([^/\s?#]+)""")
                .find(s)
            val short = Regex("""([\w.-]+)/([\w.-]+)""").matchEntire(s)
            val ownerName = page?.destructured ?: short?.destructured
            if (ownerName != null) {
                val (owner, name) = ownerName
                val sub = "$owner-$name".lowercase().replace('_', '-').replace('.', '-')
                return "https://$sub.hf.space"
            }
            if (!s.startsWith("http")) s = "https://$s"
            return s
        }

        /** يبني قالب data: النص → {{prompt}}، السلبي → {{negative}}، أول صورة → {{image}}، seed → {{seed}}. */
        fun buildTemplate(ep: EndpointInfo): String {
            fun key(p: ParamInfo) = "${p.label} ${p.name}".lowercase()
            fun isText(p: ParamInfo) = p.component.lowercase() in setOf("textbox", "textarea")

            // رقم المشهد في اسم الحقل (Scene 2 …): نملأ المشهد 1 أو الحقول بلا رقم فقط
            fun sceneNo(p: ParamInfo) =
                Regex("""(?:scene|shot|clip|مشهد)\D{0,3}(\d+)""").find(key(p))?.groupValues?.get(1)?.toIntOrNull()

            fun promptLike(p: ParamInfo) = isText(p) && "negative" !in key(p) &&
                ("prompt" in key(p) || "scene" in key(p) || "description" in key(p) || "caption" in key(p)) &&
                (sceneNo(p) ?: 1) == 1

            val promptSet: Set<Int> = ep.params.indices.filter { promptLike(ep.params[it]) }.toSet().ifEmpty {
                val first = ep.params.indexOfFirst { isText(it) && "negative" !in key(it) }
                if (first >= 0) setOf(first) else emptySet()
            }
            val negIdx = ep.params.indexOfFirst { isText(it) && "negative" in key(it) }
            val imageIdx = ep.params.indexOfFirst { it.component.equals("image", ignoreCase = true) }
            val seedIdx = ep.params.indexOfFirst {
                "seed" in key(it) && it.component.lowercase() in setOf("number", "slider")
            }

            val arr = JSONArray()
            ep.params.forEachIndexed { i, p ->
                val c = p.component.lowercase()
                when {
                    i in promptSet -> arr.put("{{prompt}}")
                    i == negIdx -> arr.put("{{negative}}")
                    i == imageIdx -> arr.put("{{image}}")
                    i == seedIdx -> arr.put("{{seed}}")
                    p.hasDefault -> arr.put(p.default ?: JSONObject.NULL)
                    c == "checkbox" -> arr.put(false)
                    c == "number" || c == "slider" -> arr.put(0)
                    isText(p) -> arr.put("")
                    else -> arr.put(JSONObject.NULL)
                }
            }
            return arr.toString()
                .replace("\"{{image}}\"", "{{image}}")
                .replace("\"{{seed}}\"", "{{seed}}")
        }
    }
}

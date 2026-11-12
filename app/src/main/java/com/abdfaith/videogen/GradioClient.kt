package com.abdfaith.videogen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** عميل HTTP بسيط لواجهة Gradio (يدعم /gradio_api والمسار القديم). */
class GradioClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.MINUTES)
        .writeTimeout(3, TimeUnit.MINUTES)
        .build()

    private fun Request.Builder.auth(token: String): Request.Builder =
        if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") else this

    private fun base(url: String) = url.trim().trimEnd('/')

    /** يرفع صورة إلى الـ Space ويعيد كائن FileData جاهزاً للإدراج في data. */
    suspend fun upload(cfg: ProviderConfig, file: File): JSONObject = withContext(Dispatchers.IO) {
        val b = base(cfg.spaceUrl)
        var lastErr = "فشل رفع الصورة"
        for (prefix in listOf("/gradio_api", "")) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "files", file.name,
                    file.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            val req = Request.Builder().url("$b$prefix/upload").auth(cfg.hfToken).post(body).build()
            val result: JSONObject? = http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    JSONObject()
                        .put("path", JSONArray(text).getString(0))
                        .put("meta", JSONObject().put("_type", "gradio.FileData"))
                } else {
                    lastErr = "فشل رفع الصورة (${r.code})"
                    null
                }
            }
            if (result != null) return@withContext result
        }
        throw IOException(lastErr)
    }

    /** ينفّذ استدعاء API ويعيد مصفوفة النتائج. */
    suspend fun call(cfg: ProviderConfig, data: JSONArray): JSONArray = withContext(Dispatchers.IO) {
        val b = base(cfg.spaceUrl)
        val api = cfg.apiName.trim().trimStart('/')
        var eventId: String? = null
        var usedPrefix = ""
        var err = "فشل الاتصال بالخدمة"

        for (prefix in listOf("/gradio_api", "")) {
            val req = Request.Builder().url("$b$prefix/call/$api").auth(cfg.hfToken)
                .post(
                    JSONObject().put("data", data).toString()
                        .toRequestBody("application/json".toMediaType())
                ).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    eventId = JSONObject(text).getString("event_id")
                    usedPrefix = prefix
                } else {
                    err = "فشل الطلب (${r.code}): ${text.take(200)}"
                }
            }
            if (eventId != null) break
        }
        val id = eventId ?: throw IOException(err)

        val req = Request.Builder().url("$b$usedPrefix/call/$api/$id").auth(cfg.hfToken).get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("فشل قراءة النتيجة (${r.code})")
            val src = r.body?.source() ?: throw IOException("استجابة فارغة")
            var event = ""
            while (true) {
                val line = src.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        if (event == "complete") return@withContext JSONArray(payload)
                        if (event == "error") {
                            val detail = payload.trim().trim('"')
                            throw IOException(
                                if (detail.isEmpty() || detail == "null") {
                                    "الخدمة رفضت الطلب دون تفاصيل. الأسباب الشائعة: " +
                                        "(1) نفاد حصة GPU المجانية: أضف توكن Hugging Face في الإعدادات وأعد المحاولة لاحقاً، " +
                                        "(2) قالب المدخلات لا يطابق الدالة: أعد ملأه بزر «اختبار الاتصال»، " +
                                        "(3) الـ Space يتطلب صورة بداية، " +
                                        "(4) خطأ داخلي في الـ Space: جرّب Space آخر."
                                } else {
                                    "خطأ من الخدمة: ${detail.take(300)}"
                                }
                            )
                        }
                    }
                }
            }
        }
        throw IOException("انتهى البث دون نتيجة (قد تكون حصة GPU المجانية نفدت)")
    }

    /** يحمّل أول ملف في النتيجة إلى dest. */
    suspend fun download(cfg: ProviderConfig, result: JSONArray, dest: File) =
        withContext(Dispatchers.IO) {
            val b = base(cfg.spaceUrl)
            val url = findFileUrl(result, b) ?: throw IOException("لا يوجد ملف فيديو في النتيجة")
            val builder = Request.Builder().url(url)
            if (url.startsWith(b)) builder.auth(cfg.hfToken)
            http.newCall(builder.get().build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("فشل تحميل الفيديو (${r.code})")
                val body = r.body ?: throw IOException("ملف فارغ")
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (dest.length() == 0L) throw IOException("الفيديو المحمّل فارغ")
        }

    private fun findFileUrl(node: Any?, base: String): String? {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) {
                findFileUrl(node.opt(i), base)?.let { return it }
            }
            is JSONObject -> {
                val url = node.optString("url", "")
                if (url.startsWith("http")) return url
                val path = node.optString("path", "")
                if (path.isNotEmpty() && path != "null") return "$base/gradio_api/file=$path"
                val keys = node.keys()
                while (keys.hasNext()) {
                    findFileUrl(node.opt(keys.next()), base)?.let { return it }
                }
            }
        }
        return null
    }
}

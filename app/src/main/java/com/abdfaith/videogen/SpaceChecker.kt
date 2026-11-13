package com.abdfaith.videogen

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** نتيجة بند فحص: ok=true نجاح، false فشل، null تحذير. */
data class CheckItem(val ok: Boolean?, val text: String) {
    fun line(): String = when (ok) {
        true -> "✓ "
        false -> "✗ "
        null -> "⚠ "
    } + text
}

/**
 * فحص الـ Space على مستويين:
 * 1) فحص سريع (لا يستهلك حصة): هل يردّ؟ هل الدالة موجودة وتُرجع فيديو؟ هل القالب يطابق مدخلاتها؟
 * 2) تجربة توليد حقيقية (تستهلك جزءاً من الحصة): تنفّذ طلباً قصيراً وتتحقق من وصول فيديو.
 */
class SpaceChecker {
    private val inspector = SpaceInspector(12, 25)
    private val client = GradioClient()

    suspend fun staticCheck(cfg: ProviderConfig): List<CheckItem> {
        if (cfg.spaceUrl.isBlank()) return listOf(CheckItem(false, "رابط الـ Space فارغ"))
        if (cfg.apiName.isBlank()) return listOf(CheckItem(false, "api_name فارغ"))

        val eps = try {
            inspector.inspect(cfg.spaceUrl, cfg.hfToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return listOf(CheckItem(false, e.message ?: "تعذّر الوصول إلى الـ Space"))
        }

        val out = mutableListOf<CheckItem>()
        out += CheckItem(true, "الـ Space يعمل ويردّ (${eps.size} دالة)")

        val wanted = cfg.apiName.trim().trimStart('/')
        val ep = eps.firstOrNull { it.apiName == wanted }
        if (ep == null) {
            val names = eps.joinToString { it.apiName }.ifEmpty { "لا شيء" }
            out += CheckItem(false, "الدالة «$wanted» غير موجودة. المتاحة: $names")
            return out
        }
        out += CheckItem(true, "الدالة /$wanted موجودة (${ep.params.size} مدخلات)")
        out += if (ep.returnsVideo) {
            CheckItem(true, "الدالة تُرجع فيديو")
        } else {
            CheckItem(null, "الدالة لا تعلن عن مخرج فيديو؛ قد تكون دالة إرسال. اختر دالة عليها ★ من «اختبار الاتصال».")
        }

        val filled = fill(cfg, "test")
        val arr = try {
            JSONArray(filled)
        } catch (e: Exception) {
            out += CheckItem(false, "قالب data ليس JSON صالحاً")
            return out
        }
        when {
            arr.length() > ep.params.size ->
                out += CheckItem(false, "القالب فيه ${arr.length()} عنصراً والدالة تتوقع ${ep.params.size}")
            arr.length() < ep.params.size ->
                out += CheckItem(null, "القالب فيه ${arr.length()} عنصراً والدالة تتوقع ${ep.params.size}؛ قد تُملأ الباقي بقيم افتراضية")
            else -> out += CheckItem(true, "عدد عناصر القالب يطابق مدخلات الدالة")
        }
        if ("{{prompt}}" !in cfg.dataTemplate) {
            out += CheckItem(null, "القالب لا يحوي {{prompt}}: لن يؤثر وصف المشهد في النتيجة")
        }
        val needsImage = ep.params.any { it.component.equals("image", ignoreCase = true) && !it.hasDefault }
        if (needsImage && "{{image}}" !in cfg.dataTemplate) {
            out += CheckItem(null, "الدالة فيها حقل صورة ولا يوجد {{image}} في القالب")
        }
        if (cfg.hfToken.isBlank()) {
            out += CheckItem(null, "لا يوجد توكن Hugging Face: حصة الزائر المجانية ضئيلة وقد يفشل التوليد")
        }
        return out
    }

    /** يولّد فعلياً بوصف قصير بلا صورة. يستهلك جزءاً من الحصة. */
    suspend fun liveTest(cfg: ProviderConfig): List<CheckItem> {
        val data = try {
            JSONArray(fill(cfg, "a red ball rolling slowly on a wooden table, soft light"))
        } catch (e: Exception) {
            return listOf(CheckItem(false, "قالب data ليس JSON صالحاً"))
        }
        val t0 = System.currentTimeMillis()
        return try {
            val result = client.call(cfg, data)
            val secs = (System.currentTimeMillis() - t0) / 1000
            val url = client.findVideoUrl(result, cfg.spaceUrl)
            if (url != null) {
                listOf(CheckItem(true, "التوليد الحقيقي نجح خلال $secs ثانية وأرجع فيديو"))
            } else {
                listOf(CheckItem(false, "الخدمة ردّت بلا فيديو. الرد: ${result.toString().take(300)}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(CheckItem(false, "فشل التوليد التجريبي: ${e.message}"))
        }
    }

    private fun fill(cfg: ProviderConfig, prompt: String): String {
        fun esc(s: String): String = JSONObject.quote(s).let { it.substring(1, it.length - 1) }
        return cfg.dataTemplate
            .replace("{{prompt}}", esc(prompt))
            .replace("{{negative}}", esc(cfg.negativePrompt))
            .replace("{{image}}", "null")
            .replace("{{seed}}", "1")
    }
}

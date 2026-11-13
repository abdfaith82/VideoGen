package com.abdfaith.videogen

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ProjectViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    val settings = SettingsStore(app)
    private val client = GradioClient()
    private val ai = PromptAi()

    val projects = MutableStateFlow(store.loadAll())
    val current = MutableStateFlow<Project?>(null)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("")
    /** فهرس المشهد الذي يعمل عليه مساعد البرومبت الآن (-1 = لا شيء). */
    val aiIndex = MutableStateFlow(-1)
    private var job: Job? = null
    private val mu = Mutex()

    // ---------- المشاريع ----------

    private fun newScene(prompt: String = "") = Scene(id = UUID.randomUUID().toString(), prompt = prompt)

    private fun commit(p0: Project) {
        val p = p0.copy(updatedAt = System.currentTimeMillis())
        runCatching { store.save(p) }
        current.value = p
        val list = projects.value
        projects.value = if (list.any { it.id == p.id }) {
            list.map { if (it.id == p.id) p else it }
        } else {
            listOf(p) + list
        }
    }

    /** حفظ من مهام الخلفية: لا يغيّر المشروع المفتوح إلا إن كان هو نفسه. */
    private fun commitBg(p0: Project) {
        val p = p0.copy(updatedAt = System.currentTimeMillis())
        runCatching { store.save(p) }
        if (current.value?.id == p.id) current.value = p
        projects.value = projects.value.map { if (it.id == p.id) p else it }
    }

    private suspend fun patchScene(pid: String, sid: String, f: (Scene) -> Scene) = mu.withLock {
        val base = projects.value.firstOrNull { it.id == pid } ?: return@withLock
        commitBg(base.copy(scenes = base.scenes.map { if (it.id == sid) f(it) else it }))
    }

    private fun sceneOf(pid: String, sid: String): Scene? =
        projects.value.firstOrNull { it.id == pid }?.scenes?.firstOrNull { it.id == sid }

    fun createProject(title: String) {
        commit(
            Project(
                id = UUID.randomUUID().toString(),
                title = title.trim().ifBlank { "مشروع بدون عنوان" },
                scenes = listOf(newScene())
            )
        )
        status.value = ""
    }

    fun open(id: String) {
        current.value = projects.value.firstOrNull { it.id == id }
        status.value = ""
    }

    fun delete(id: String) {
        if (current.value?.id == id) current.value = null
        store.delete(id)
        projects.value = projects.value.filterNot { it.id == id }
    }

    // ---------- تحرير المشاهد ----------

    /** ينشئ ملفاً مؤقتاً ويعيد Uri آمناً لتمريره إلى تطبيق الكاميرا. */
    fun newCameraUri(): Uri {
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File.createTempFile("shot_", ".jpg", dir)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    fun setStartImage(uri: Uri) {
        val p = current.value ?: return
        val ctx = getApplication<Application>()
        val f = File(store.dir(p.id), "start_${System.currentTimeMillis()}.jpg")
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { i ->
                f.outputStream().use { o -> i.copyTo(o) }
            } ?: error("تعذّر فتح الصورة")
        }.onSuccess {
            p.startImagePath?.let { File(it).delete() }
            commit(p.copy(startImagePath = f.absolutePath))
        }.onFailure {
            status.value = "تعذّر قراءة الصورة"
        }
    }

    fun clearStartImage() {
        val p = current.value ?: return
        p.startImagePath?.let { File(it).delete() }
        commit(p.copy(startImagePath = null))
    }

    fun addScene() {
        val p = current.value ?: return
        commit(p.copy(scenes = p.scenes + newScene()))
    }

    fun removeScene(index: Int) {
        val p = current.value ?: return
        val s = p.scenes.getOrNull(index) ?: return
        s.videoPath?.let { File(it).delete() }
        s.lastFramePath?.let { File(it).delete() }
        val rest = p.scenes.filterIndexed { i, _ -> i != index }
        commit(p.copy(scenes = rest.ifEmpty { listOf(newScene()) }))
    }

    fun updatePrompt(index: Int, text: String) {
        val p = current.value ?: return
        commit(p.mapScene(index) { it.copy(prompt = text) })
    }

    /** كل سطر غير فارغ يصبح مشهداً. */
    fun setScenesFromScript(script: String) {
        val p = current.value ?: return
        val lines = script.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        commit(p.copy(scenes = lines.map { newScene(it) }, outputPath = null))
    }

    fun resetScene(index: Int) {
        val p = current.value ?: return
        val s = p.scenes.getOrNull(index) ?: return
        s.videoPath?.let { File(it).delete() }
        s.lastFramePath?.let { File(it).delete() }
        commit(
            p.mapScene(index) {
                it.copy(status = SceneStatus.PENDING, videoPath = null, lastFramePath = null, error = null)
            }
        )
    }

    private fun Project.mapScene(index: Int, f: (Scene) -> Scene) =
        copy(scenes = scenes.mapIndexed { i, s -> if (i == index) f(s) else s })

    // ---------- مساعد البرومبت ----------

    /** يحوّل وصف المشهد (بأي لغة) إلى برومبت احترافي ويضعه مكانه. */
    fun enhanceScene(index: Int) {
        val p = current.value ?: return
        val sc = p.scenes.getOrNull(index) ?: return
        if (busy.value || aiIndex.value >= 0) return
        if (sc.prompt.isBlank()) {
            status.value = "اكتب وصفاً للمشهد أولاً (بأي لغة)"
            return
        }
        viewModelScope.launch {
            aiIndex.value = index
            status.value = "جارٍ توليد برومبت احترافي…"
            try {
                val r = ai.enhance(sc.prompt)
                sc.videoPath?.let { File(it).delete() }
                sc.lastFramePath?.let { File(it).delete() }
                patchScene(p.id, sc.id) {
                    it.copy(
                        prompt = r.value,
                        status = SceneStatus.PENDING,
                        videoPath = null,
                        lastFramePath = null,
                        error = null
                    )
                }
                status.value = if (r.fromAi) {
                    "تم توليد البرومبت الاحترافي ✓"
                } else {
                    "الخدمة السحابية غير متاحة الآن: استُخدم قالب محلي. اكتب الوصف بالإنجليزية لنتيجة أفضل."
                }
            } finally {
                aiIndex.value = -1
            }
        }
    }

    // ---------- التوليد ----------

    private fun launchJob(pid: String, block: suspend () -> Unit) {
        if (busy.value) return
        job = viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                status.value = "تم الإيقاف"
                throw e
            } catch (e: Exception) {
                status.value = "خطأ: ${e.message}"
            } finally {
                busy.value = false
                // أي مشهد بقي بحالة التشغيل يعود للانتظار
                withContext(NonCancellable) { resetRunning(pid) }
            }
        }
    }

    private suspend fun resetRunning(pid: String) = mu.withLock {
        val base = projects.value.firstOrNull { it.id == pid } ?: return@withLock
        if (base.scenes.any { it.status == SceneStatus.RUNNING }) {
            commitBg(
                base.copy(scenes = base.scenes.map {
                    if (it.status == SceneStatus.RUNNING) it.copy(status = SceneStatus.PENDING) else it
                })
            )
        }
    }

    fun generate() {
        val p = current.value ?: return
        launchJob(p.id) { runPipeline(p.id) }
    }

    /** يقسّم المشهد إلى [count] لقطات متتابعة ثم يولّدها ويجمّعها بضغطة واحدة. */
    fun splitAndGenerate(index: Int, count: Int = 20) {
        val p = current.value ?: return
        val sc = p.scenes.getOrNull(index) ?: return
        if (busy.value || aiIndex.value >= 0) return
        if (sc.prompt.isBlank()) {
            status.value = "اكتب وصف المشهد أولاً"
            return
        }
        launchJob(p.id) {
            status.value = "تقسيم المشهد إلى $count لقطة…"
            val r = ai.split(sc.prompt, count)
            sc.videoPath?.let { File(it).delete() }
            sc.lastFramePath?.let { File(it).delete() }
            mu.withLock {
                val base = projects.value.firstOrNull { it.id == p.id } ?: return@withLock
                val shots = r.value.map { newScene(it) }
                val list = base.scenes.flatMap { if (it.id == sc.id) shots else listOf(it) }
                commitBg(base.copy(scenes = list, outputPath = null))
            }
            status.value = if (r.fromAi) {
                "تم التقسيم إلى ${r.value.size} لقطة — بدء التوليد…"
            } else {
                "تقسيم محلي (الخدمة السحابية غير متاحة) إلى ${r.value.size} لقطة — بدء التوليد…"
            }
            runPipeline(p.id)
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** يولّد مشهداً واحداً ويحفظ نتيجته. يعيد true عند النجاح. */
    private suspend fun generateOne(
        cfg: ProviderConfig,
        dir: File,
        pid: String,
        sid: String,
        prompt: String,
        image: String?
    ): Boolean {
        patchScene(pid, sid) { it.copy(status = SceneStatus.RUNNING, error = null) }
        return try {
            val imageFile = image?.let { File(it) }?.takeIf { it.exists() }
            val data = buildData(cfg, prompt, imageFile)
            val result = client.call(cfg, data)
            val video = File(dir, "scene_$sid.mp4")
            client.download(cfg, result, video)
            val frame = File(dir, "scene_${sid}_last.jpg")
            val ok = FrameExtractor.lastFrame(video.absolutePath, frame)
            patchScene(pid, sid) {
                it.copy(
                    status = SceneStatus.DONE,
                    videoPath = video.absolutePath,
                    lastFramePath = if (ok) frame.absolutePath else null,
                    error = null
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            patchScene(pid, sid) { it.copy(status = SceneStatus.FAILED, error = e.message ?: "فشل غير معروف") }
            false
        }
    }

    private fun isDone(s: Scene?): Boolean =
        s != null && s.status == SceneStatus.DONE && s.videoPath != null && File(s.videoPath).exists()

    private suspend fun runPipeline(pid: String) {
        val cfg = settings.load()
        if (cfg.spaceUrl.isBlank() || cfg.apiName.isBlank()) {
            status.value = "اضبط رابط الخدمة من الإعدادات أولاً"
            return
        }
        val start = projects.value.firstOrNull { it.id == pid } ?: return
        val dir = store.dir(pid)
        val todo = start.scenes.filter { it.prompt.isNotBlank() }
        if (todo.isEmpty()) {
            status.value = "اكتب وصف مشهد واحد على الأقل"
            return
        }
        val total = todo.size
        val parallel = if (cfg.chainFrames) 1 else cfg.parallel.coerceIn(1, 4)

        if (parallel == 1) {
            // تتابع: كل مشهد يبدأ من آخر إطار في السابق عند تفعيل الربط
            var carry: String? = start.startImagePath
            var n = 0
            for (sc in todo) {
                n++
                val cur = sceneOf(pid, sc.id) ?: continue
                if (isDone(cur)) {
                    if (cfg.chainFrames) carry = cur.lastFramePath ?: carry
                    continue
                }
                status.value = "توليد المشهد $n من $total… (قد يستغرق دقائق)"
                val ok = generateOne(cfg, dir, pid, cur.id, cur.prompt, carry)
                val after = sceneOf(pid, cur.id)
                if (!ok) {
                    status.value = "توقف التوليد عند المشهد $n: ${after?.error}"
                    return
                }
                if (cfg.chainFrames) carry = after?.lastFramePath ?: carry
            }
        } else {
            // توازٍ: كل المشاهد تبدأ من صورة البداية نفسها (لا ربط بين المشاهد)
            val pending = todo.filter { !isDone(sceneOf(pid, it.id)) }
            val gate = Semaphore(parallel)
            val finished = AtomicInteger(total - pending.size)
            status.value = "توليد متوازٍ ($parallel معاً): ${finished.get()} من $total"
            val results = coroutineScope {
                pending.map { sc ->
                    async {
                        gate.withPermit {
                            val ok = generateOne(cfg, dir, pid, sc.id, sc.prompt, start.startImagePath)
                            status.value = "توليد متوازٍ: ${finished.incrementAndGet()} من $total"
                            ok
                        }
                    }
                }.awaitAll()
            }
            val failed = results.count { !it }
            if (failed > 0) {
                status.value = "فشل $failed مشهد من ${pending.size}. اضغط «متابعة التوليد» لإعادة الفاشلة فقط."
                return
            }
        }

        val fresh = projects.value.firstOrNull { it.id == pid } ?: return
        val clips = fresh.scenes.filter { it.prompt.isNotBlank() }.mapNotNull { it.videoPath }
            .filter { File(it).exists() }
        if (clips.isEmpty()) {
            status.value = "لا توجد مقاطع للتجميع"
            return
        }
        status.value = "تجميع المقاطع في فيديو واحد…"
        val outPath = stitchTo(pid, clips)
        mu.withLock {
            projects.value.firstOrNull { it.id == pid }?.let { commitBg(it.copy(outputPath = outPath)) }
        }
        status.value = "اكتمل الفيديو ✓"
    }

    private suspend fun buildData(cfg: ProviderConfig, prompt: String, image: File?): JSONArray {
        val imageJson = if (image != null) client.upload(cfg, image).toString() else "null"
        val seed = Random.nextInt(0, Int.MAX_VALUE)
        fun esc(s: String): String = JSONObject.quote(s).let { it.substring(1, it.length - 1) }
        val filled = cfg.dataTemplate
            .replace("{{prompt}}", esc(prompt))
            .replace("{{negative}}", esc(cfg.negativePrompt))
            .replace("{{image}}", imageJson)
            .replace("{{seed}}", seed.toString())
        return try {
            JSONArray(filled)
        } catch (e: Exception) {
            throw IllegalArgumentException("قالب البيانات في الإعدادات ليس JSON صالحاً")
        }
    }

    /** يجمّع المقاطع في ملف جديد ويحذف الناتج القديم. يعيد مسار الملف الجديد. */
    private suspend fun stitchTo(pid: String, clips: List<String>): String {
        val out = File(store.dir(pid), "final_${System.currentTimeMillis()}.mp4")
        VideoStitcher.stitch(getApplication(), clips, out)
        projects.value.firstOrNull { it.id == pid }?.outputPath?.let { File(it).delete() }
        return out.absolutePath
    }

    fun restitch() {
        val p = current.value ?: return
        val clips = p.scenes.filter { it.prompt.isNotBlank() }.mapNotNull { it.videoPath }
            .filter { File(it).exists() }
        if (clips.isEmpty()) return
        launchJob(p.id) {
            status.value = "تجميع المقاطع…"
            val outPath = stitchTo(p.id, clips)
            mu.withLock {
                projects.value.firstOrNull { it.id == p.id }?.let { commitBg(it.copy(outputPath = outPath)) }
            }
            status.value = "تم التجميع ✓"
        }
    }

    // ---------- التصدير ----------

    fun exportToGallery() {
        val p = current.value ?: return
        val src = p.outputPath?.let { File(it) }?.takeIf { it.exists() } ?: run {
            status.value = "لا يوجد فيديو نهائي"
            return
        }
        viewModelScope.launch {
            status.value = try {
                withContext(Dispatchers.IO) { saveToMovies(src) }
            } catch (e: Exception) {
                "تعذّر الحفظ: ${e.message}"
            }
        }
    }

    private fun saveToMovies(src: File): String {
        val ctx = getApplication<Application>()
        val name = "VideoGen_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoGen")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("رفض النظام إنشاء الملف")
            ctx.contentResolver.openOutputStream(uri)?.use { o ->
                src.inputStream().use { it.copyTo(o) }
            } ?: error("تعذّر فتح الملف للكتابة")
            "تم الحفظ في المعرض (Movies/VideoGen) ✓"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
            src.copyTo(File(dir, name), overwrite = true)
            "تم الحفظ في: ${dir.absolutePath}"
        }
    }

    // ---------- سجل الانهيار ----------

    fun readCrash(): String? {
        val f = File(getApplication<Application>().filesDir, App.CRASH_FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clearCrash() {
        File(getApplication<Application>().filesDir, App.CRASH_FILE).delete()
    }
}

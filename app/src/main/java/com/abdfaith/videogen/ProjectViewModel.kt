package com.abdfaith.videogen

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.random.Random

class ProjectViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    val settings = SettingsStore(app)
    private val client = GradioClient()

    val projects = MutableStateFlow(store.loadAll())
    val current = MutableStateFlow<Project?>(null)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("")
    private var job: Job? = null

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

    // ---------- التوليد ----------

    fun generate() {
        val start = current.value ?: return
        if (busy.value) return
        job = viewModelScope.launch {
            busy.value = true
            try {
                runPipeline(start)
            } catch (e: CancellationException) {
                status.value = "تم الإيقاف"
                throw e
            } catch (e: Exception) {
                status.value = "خطأ: ${e.message}"
            } finally {
                busy.value = false
                // أي مشهد بقي بحالة التشغيل يعود للانتظار
                current.value?.let { cur ->
                    if (cur.scenes.any { it.status == SceneStatus.RUNNING }) {
                        commit(cur.copy(scenes = cur.scenes.map {
                            if (it.status == SceneStatus.RUNNING) it.copy(status = SceneStatus.PENDING) else it
                        }))
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun runPipeline(start: Project) {
        val cfg = settings.load()
        if (cfg.spaceUrl.isBlank() || cfg.apiName.isBlank()) {
            status.value = "اضبط رابط الخدمة من الإعدادات أولاً"
            return
        }
        var p = start
        val dir = store.dir(p.id)
        var carry: String? = p.startImagePath
        val total = p.scenes.count { it.prompt.isNotBlank() }
        var n = 0

        for (i in p.scenes.indices) {
            val sc = p.scenes[i]
            if (sc.prompt.isBlank()) continue
            n++
            if (sc.status == SceneStatus.DONE && sc.videoPath != null && File(sc.videoPath).exists()) {
                if (cfg.chainFrames) carry = sc.lastFramePath ?: carry
                continue
            }
            status.value = "توليد المشهد $n من $total… (قد يستغرق دقائق)"
            p = p.mapScene(i) { it.copy(status = SceneStatus.RUNNING, error = null) }
            commit(p)

            try {
                val imageFile = carry?.let { File(it) }?.takeIf { it.exists() }
                val data = buildData(cfg, sc.prompt, imageFile)
                val result = client.call(cfg, data)
                val video = File(dir, "scene_${sc.id}.mp4")
                client.download(cfg, result, video)
                val frame = File(dir, "scene_${sc.id}_last.jpg")
                val ok = FrameExtractor.lastFrame(video.absolutePath, frame)
                p = p.mapScene(i) {
                    it.copy(
                        status = SceneStatus.DONE,
                        videoPath = video.absolutePath,
                        lastFramePath = if (ok) frame.absolutePath else null,
                        error = null
                    )
                }
                commit(p)
                if (cfg.chainFrames && ok) carry = frame.absolutePath
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p = p.mapScene(i) { it.copy(status = SceneStatus.FAILED, error = e.message ?: "فشل غير معروف") }
                commit(p)
                status.value = "توقف التوليد عند المشهد $n: ${e.message}"
                return
            }
        }

        val clips = p.scenes.filter { it.prompt.isNotBlank() }.mapNotNull { it.videoPath }
        if (clips.isEmpty()) {
            status.value = "اكتب وصف مشهد واحد على الأقل"
            return
        }
        status.value = "تجميع المقاطع في فيديو واحد…"
        p = stitchInto(p, clips)
        commit(p)
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

    private suspend fun stitchInto(p: Project, clips: List<String>): Project {
        val out = File(store.dir(p.id), "final_${System.currentTimeMillis()}.mp4")
        VideoStitcher.stitch(getApplication(), clips, out)
        p.outputPath?.let { File(it).delete() }
        return p.copy(outputPath = out.absolutePath)
    }

    fun restitch() {
        val p = current.value ?: return
        if (busy.value) return
        val clips = p.scenes.filter { it.prompt.isNotBlank() }.mapNotNull { it.videoPath }
            .filter { File(it).exists() }
        if (clips.isEmpty()) return
        job = viewModelScope.launch {
            busy.value = true
            try {
                status.value = "تجميع المقاطع…"
                commit(stitchInto(p, clips))
                status.value = "تم التجميع ✓"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "فشل التجميع: ${e.message}"
            } finally {
                busy.value = false
            }
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

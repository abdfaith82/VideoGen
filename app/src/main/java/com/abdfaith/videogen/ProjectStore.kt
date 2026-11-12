package com.abdfaith.videogen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProjectStore(ctx: Context) {
    private val root = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun loadAll(): List<Project> =
        root.listFiles()
            ?.mapNotNull { d ->
                runCatching { fromJson(JSONObject(File(d, "project.json").readText())) }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun save(p: Project) {
        File(dir(p.id), "project.json").writeText(toJson(p).toString())
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    private fun toJson(p: Project) = JSONObject()
        .put("id", p.id)
        .put("title", p.title)
        .put("startImagePath", p.startImagePath ?: JSONObject.NULL)
        .put("outputPath", p.outputPath ?: JSONObject.NULL)
        .put("updatedAt", p.updatedAt)
        .put("scenes", JSONArray().also { arr ->
            p.scenes.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("prompt", s.prompt)
                        .put("status", s.status.name)
                        .put("videoPath", s.videoPath ?: JSONObject.NULL)
                        .put("lastFramePath", s.lastFramePath ?: JSONObject.NULL)
                        .put("error", s.error ?: JSONObject.NULL)
                )
            }
        })

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun fromJson(o: JSONObject): Project {
        val arr = o.optJSONArray("scenes") ?: JSONArray()
        val scenes = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val st = runCatching { SceneStatus.valueOf(s.optString("status")) }
                .getOrDefault(SceneStatus.PENDING)
            Scene(
                id = s.getString("id"),
                prompt = s.optString("prompt"),
                // مشهد كان قيد التوليد عند إغلاق التطبيق يعود إلى الانتظار
                status = if (st == SceneStatus.RUNNING) SceneStatus.PENDING else st,
                videoPath = s.str("videoPath"),
                lastFramePath = s.str("lastFramePath"),
                error = s.str("error")
            )
        }
        return Project(
            id = o.getString("id"),
            title = o.optString("title"),
            startImagePath = o.str("startImagePath"),
            scenes = scenes,
            outputPath = o.str("outputPath"),
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }
}

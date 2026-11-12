package com.abdfaith.videogen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File

private val LtrStyle = TextStyle(textDirection = TextDirection.Ltr)

@Composable
fun AppRoot(vm: ProjectViewModel) {
    var route by rememberSaveable { mutableStateOf("home") }
    var crash by remember { mutableStateOf(vm.readCrash()) }

    val crashText = crash
    if (crashText != null) {
        CrashScreen(crashText) {
            vm.clearCrash()
            crash = null
        }
        return
    }

    BackHandler(enabled = route != "home") { route = "home" }

    when (route) {
        "project" -> ProjectScreen(vm) { route = "home" }
        "settings" -> SettingsScreen(vm) { route = "home" }
        else -> HomeScreen(vm, onOpen = { route = "project" }, onSettings = { route = "settings" })
    }
}

@Composable
fun CrashScreen(text: String, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("حدث انهيار في التشغيل السابق", style = MaterialTheme.typography.titleMedium)
        Text("انسخ النص التالي وأرسله لإصلاح المشكلة:", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall.merge(LtrStyle))
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("متابعة") }
    }
}

// ------------------------------------------------------------------ الرئيسية

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ProjectViewModel, onOpen: () -> Unit, onSettings: () -> Unit) {
    val projects by vm.projects.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("استوديو الفيديو بالذكاء الاصطناعي") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "الإعدادات") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { title = ""; showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("مشروع جديد") }
            )
        }
    ) { pad ->
        if (projects.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "أنشئ مشروعاً، اكتب مشاهدك (نصاً وصورة اختيارية)، وسيولّد التطبيق كل مشهد ثم يربطها في فيديو طويل واحد.",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { vm.open(p.id); onOpen() }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium)
                                val done = p.scenes.count { it.status == SceneStatus.DONE }
                                Text(
                                    "${p.scenes.size} مشهد · مكتمل $done",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { vm.delete(p.id) }) {
                                Icon(Icons.Default.Delete, "حذف")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("مشروع جديد") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("اسم المشروع") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createProject(title)
                    showNew = false
                    onOpen()
                }) { Text("إنشاء") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("إلغاء") } }
        )
    }
}

// ------------------------------------------------------------------ المشروع

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(vm: ProjectViewModel, onBack: () -> Unit) {
    val project by vm.current.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    val p = project
    if (p == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.setStartImage(uri)
    }
    var showScript by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // صورة البداية
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("صورة البداية (اختيارية)", style = MaterialTheme.typography.titleSmall)
                        val img = p.startImagePath
                        if (img != null) {
                            AsyncImage(
                                model = File(img),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                        } else {
                            Text(
                                "بدونها يُولَّد المشهد الأول من النص فقط.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            ) { Text(if (img == null) "اختر صورة" else "تغيير الصورة") }
                            if (img != null) {
                                TextButton(enabled = !busy, onClick = { vm.clearStartImage() }) { Text("إزالة") }
                            }
                        }
                    }
                }
            }

            // رأس المشاهد
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("المشاهد", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { script = ""; showScript = true }) {
                        Text("لصق سيناريو")
                    }
                    TextButton(enabled = !busy, onClick = { vm.addScene() }) { Text("+ مشهد") }
                }
            }

            itemsIndexed(p.scenes, key = { _, s -> s.id }) { i, s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = s.prompt,
                            onValueChange = { vm.updatePrompt(i, it) },
                            enabled = !busy,
                            label = { Text("وصف المشهد ${i + 1} (يُفضَّل بالإنجليزية للدقة)") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (s.status) {
                                    SceneStatus.PENDING -> "في الانتظار"
                                    SceneStatus.RUNNING -> "جارٍ التوليد…"
                                    SceneStatus.DONE -> "تم ✓"
                                    SceneStatus.FAILED -> "فشل"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (s.status == SceneStatus.DONE || s.status == SceneStatus.FAILED) {
                                IconButton(enabled = !busy, onClick = { vm.resetScene(i) }) {
                                    Icon(Icons.Default.Refresh, "إعادة المشهد")
                                }
                            }
                            IconButton(enabled = !busy, onClick = { vm.removeScene(i) }) {
                                Icon(Icons.Default.Delete, "حذف المشهد")
                            }
                        }
                        val err = s.error
                        if (err != null && s.status == SceneStatus.FAILED) {
                            Text(
                                err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // التحكم
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!busy) {
                            Button(
                                onClick = { vm.generate() },
                                enabled = p.scenes.any { it.prompt.isNotBlank() }
                            ) {
                                Text(
                                    if (p.scenes.any { it.status == SceneStatus.DONE }) "متابعة التوليد"
                                    else "ابدأ التوليد"
                                )
                            }
                            val ready = p.scenes.filter { it.prompt.isNotBlank() }
                            if (ready.isNotEmpty() && ready.all { it.videoPath != null }) {
                                OutlinedButton(onClick = { vm.restitch() }) { Text("إعادة التجميع") }
                            }
                        } else {
                            OutlinedButton(onClick = { vm.stop() }) { Text("إيقاف") }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // المعاينة
            val out = p.outputPath
            if (out != null && File(out).exists()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("الفيديو النهائي", style = MaterialTheme.typography.titleSmall)
                            VideoPlayer(out, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            Button(onClick = { vm.exportToGallery() }) { Text("حفظ في المعرض") }
                        }
                    }
                }
            }
        }
    }

    if (showScript) {
        AlertDialog(
            onDismissRequest = { showScript = false },
            title = { Text("لصق السيناريو") },
            text = {
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text("كل سطر = مشهد") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setScenesFromScript(script)
                    showScript = false
                }) { Text("تحويل إلى مشاهد") }
            },
            dismissButton = { TextButton(onClick = { showScript = false }) { Text("إلغاء") } }
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(path: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val exo = remember(path) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { player = exo } },
        update = { it.player = exo },
        modifier = modifier
    )
}

// ------------------------------------------------------------------ الإعدادات

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ProjectViewModel, onBack: () -> Unit) {
    val initial = remember { vm.settings.load() }
    var url by remember { mutableStateOf(initial.spaceUrl) }
    var api by remember { mutableStateOf(initial.apiName) }
    var template by remember { mutableStateOf(initial.dataTemplate) }
    var token by remember { mutableStateOf(initial.hfToken) }
    var negative by remember { mutableStateOf(initial.negativePrompt) }
    var chain by remember { mutableStateOf(initial.chainFrames) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val inspector = remember { SpaceInspector() }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    var endpoints by remember { mutableStateOf(emptyList<EndpointInfo>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "التطبيق يتصل بـ Space مجاني على Hugging Face (Gradio). افتح صفحة الـ Space ثم «Use via API» " +
                    "وانسخ اسم الدالة وترتيب المدخلات. الخدمات المجانية تخضع لحصص وقد تتغير أو تتوقف.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it; saved = false },
                label = { Text("رابط الـ Space (…hf.space)") },
                textStyle = LtrStyle, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    testing = true
                    testMsg = ""
                    endpoints = emptyList()
                    scope.launch {
                        try {
                            url = SpaceInspector.normalizeUrl(url)
                            val found = inspector.inspect(url, token)
                            endpoints = found
                            testMsg = if (found.isEmpty()) {
                                "الاتصال ناجح، لكن لا توجد دوال مسمّاة في هذا الـ Space"
                            } else {
                                "الاتصال ناجح ✓ اختر الدالة المناسبة (★ = تُرجع فيديو):"
                            }
                        } catch (e: Exception) {
                            testMsg = e.message ?: "فشل الاختبار"
                        } finally {
                            testing = false
                        }
                    }
                }
            ) { Text(if (testing) "جارٍ الاختبار…" else "اختبار الاتصال") }
            if (testMsg.isNotEmpty()) {
                Text(testMsg, style = MaterialTheme.typography.bodyMedium)
            }
            endpoints.forEach { ep ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            (if (ep.returnsVideo) "★ " else "") + "/" + ep.apiName,
                            style = MaterialTheme.typography.titleSmall.merge(LtrStyle)
                        )
                        ep.params.forEachIndexed { i, pr ->
                            Text(
                                "${i + 1}. ${pr.label.ifBlank { pr.name }} (${pr.component})",
                                style = MaterialTheme.typography.bodySmall.merge(LtrStyle)
                            )
                        }
                        Button(onClick = {
                            api = ep.apiName
                            template = SpaceInspector.buildTemplate(ep)
                            saved = false
                            testMsg = "تم ملء الدالة والقالب. راجع القالب ثم اضغط حفظ."
                        }) { Text("استخدم هذه الدالة") }
                    }
                }
            }
            OutlinedTextField(
                value = api, onValueChange = { api = it; saved = false },
                label = { Text("api_name (مثل generate)") },
                textStyle = LtrStyle, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = template, onValueChange = { template = it; saved = false },
                label = { Text("قالب data (JSON): {{prompt}} {{image}} {{negative}} {{seed}}") },
                textStyle = LtrStyle, minLines = 3, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = negative, onValueChange = { negative = it; saved = false },
                label = { Text("الوصف السلبي") },
                textStyle = LtrStyle, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it; saved = false },
                label = { Text("توكن Hugging Face (اختياري، يرفع حصتك المجانية)") },
                textStyle = LtrStyle, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ربط المشاهد بآخر إطار", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "يبدأ كل مشهد من آخر إطار في السابق لاستمرارية أفضل (يتطلب Space يقبل صورة).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = chain, onCheckedChange = { chain = it; saved = false })
            }
            Button(
                onClick = {
                    vm.settings.save(
                        ProviderConfig(SpaceInspector.normalizeUrl(url), api, template, token, negative, chain)
                    )
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saved) "تم الحفظ ✓" else "حفظ") }
            Spacer(Modifier.size(8.dp))
        }
    }
}

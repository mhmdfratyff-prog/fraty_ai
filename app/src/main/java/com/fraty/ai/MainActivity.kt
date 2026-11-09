package com.fraty.ai

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraty.ai.core.FratyApi
import com.fraty.ai.history.ChatMessage
import com.fraty.ai.history.LocalStore
import kotlinx.coroutines.launch

private val Ink = Color(0xFF080A10)
private val Panel = Color(0xFF10141D)
private val Panel2 = Color(0xFF171D29)
private val Purple = Color(0xFF8055FF)
private val Cyan = Color(0xFF00C8FF)
private val White = Color(0xFFF8F9FC)
private val Muted = Color(0xFFA0A7B4)
private val ErrorRed = Color(0xFFFF7B86)

class MainActivity : ComponentActivity() {
    private lateinit var store: LocalStore
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalStore(this)
        setContent { FratyApp(store) }
    }
}

private enum class Destination(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CHAT("المحادثة", Icons.Default.Chat),
    SETTINGS("الإعدادات والتكاملات", Icons.Default.Settings)
}

private enum class TaskMode(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val endpoint: String) {
    AUTO("تلقائي", Icons.Default.AutoAwesome, "auto"),
    CHAT("محادثة", Icons.Default.Chat, "chat"),
    IMAGE("صورة", Icons.Default.Image, "image"),
    VIDEO("فيديو", Icons.Default.Videocam, "video"),
    CODE("برمجة", Icons.Default.Code, "code"),
    APP("إنشاء تطبيق", Icons.Default.Build, "app")
}

private fun inferTaskMode(prompt: String): TaskMode {
    val text = prompt.lowercase()
    val looksLikeAndroidCode = listOf("@composable", "androidmanifest.xml", "build.gradle", "androidx.compose", "class mainactivity", "componentactivity").any(text::contains)
    return when {
        listOf("فيديو", "مقطع", "video", "animate").any(text::contains) -> TaskMode.VIDEO
        listOf("صورة", "ارسم", "أنشئ صورة", "image", "thumbnail", "logo").any(text::contains) -> TaskMode.IMAGE
        looksLikeAndroidCode || listOf("تطبيق", "apk", "android app", "build an app", "build apk", "ابني التطبيق", "أنشئ تطبيق", "حوّل هذا الكود لتطبيق", "حول الكود لتطبيق").any(text::contains) -> TaskMode.APP
        listOf("كود", "برمج", "خطأ kotlin", "code", "debug", "function", "script").any(text::contains) -> TaskMode.CODE
        else -> TaskMode.CHAT
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FratyApp(store: LocalStore) {
    var destination by remember { mutableStateOf(Destination.CHAT) }
    var selectedMode by remember { mutableStateOf(TaskMode.AUTO) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var backendUrl by remember { mutableStateOf(store.backendUrl().ifBlank { BuildConfig.FRATY_BACKEND_URL }) }
    var appToken by remember { mutableStateOf(store.appToken().ifBlank { BuildConfig.FRATY_APP_TOKEN }) }
    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(store.loadMessages()) } }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(messages.toList()) { store.saveMessages(messages.toList()) }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) draft = listOf(draft, spoken).filter { it.isNotBlank() }.joinToString(" ")
        } else info = "لم يصل نص من خدمة الإملاء الصوتي في الجهاز."
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف مختار"
            info = "تم اختيار الملف ($name). رفعه وتحليله يتطلب تفعيل مسار الملفات في الخادم؛ لم يُرفع الملف."
        }
    }

    fun startDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث لإملاء رسالتك")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) { info = "لا يوجد تطبيق إملاء صوتي متاح على هذا الجهاز." }
    }

    suspend fun sendToBackend(content: String, taskMode: TaskMode = TaskMode.CHAT) {
        error = ""
        info = ""
        if (backendUrl.isBlank()) {
            error = "الخادم غير مضبوط بعد. افتح القائمة ← الإعدادات والتكاملات وأدخل عنوان HTTPS."
            return
        }
        val outgoing = ChatMessage("user", content)
        messages.add(outgoing)
        busy = true
        try {
            val api = FratyApi(backendUrl, appToken)
            val response = when (taskMode) {
                TaskMode.APP -> api.buildApp(content)
                TaskMode.IMAGE -> api.generateMedia("images", content)
                TaskMode.VIDEO -> api.generateMedia("videos", content)
                else -> {
                    val instruction = when (taskMode) {
                        TaskMode.CODE -> "أجب كمهندس برمجيات. اشرح الحل واذكر الملفات والتعليمات اللازمة."
                        else -> ""
                    }
                    val payload = messages.toList().mapIndexed { index, message ->
                        if (index == messages.lastIndex && instruction.isNotBlank()) ChatMessage(message.role, "$instruction\\n\\n${message.content}", message.createdAt) else message
                    }
                    api.sendChat(payload)
                }
            }
            messages.add(ChatMessage("assistant", response))
        } catch (e: Exception) {
            messages.remove(outgoing)
            error = e.message ?: "تعذر الوصول إلى خادم FRATY AI."
        } finally { busy = false }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Purple, secondary = Cyan, background = Ink, surface = Panel,
        onPrimary = White, onBackground = White, onSurface = White, error = ErrorRed
    )) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = true,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = Panel, drawerShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)) {
                        Spacer(Modifier.height(22.dp))
                        BrandHeader(compact = false)
                        Spacer(Modifier.height(20.dp))
                        listOf(Destination.CHAT, Destination.SETTINGS).forEach { item ->
                            NavigationDrawerItem(
                                label = { Text(item.title, color = if (destination == item) White else Muted) },
                                selected = destination == item,
                                onClick = { destination = item; scope.launch { drawerState.close() }; error = ""; info = "" },
                                icon = { Icon(item.icon, null, tint = if (destination == item) Cyan else Muted) },
                                colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = Purple.copy(alpha = .20f), unselectedContainerColor = Color.Transparent),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Imagine. Create. Build. Without Limits.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(20.dp))
                    }
                }
            ) {
                Scaffold(
                    containerColor = Ink,
                    topBar = {
                        TopAppBar(
                            title = { Column { Text("FRATY AI 1.04", fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(destination.title, color = Muted, fontSize = 11.sp) } },
                            navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "القائمة", tint = White) } },
                            actions = { Box(Modifier.padding(end = 14.dp).size(9.dp).clip(CircleShape).background(if (backendUrl.isBlank()) Muted else Cyan)) },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
                        )
                    }
                ) { padding ->
                    when (destination) {
                        Destination.CHAT -> ChatPage(
                            modifier = Modifier.padding(padding), messages = messages, draft = draft,
                            onDraft = { draft = it }, busy = busy, error = error, info = info,
                            selectedMode = selectedMode, onMode = { selectedMode = it },
                            onSend = {
                                val text = draft.trim()
                                if (text.isNotEmpty() && !busy) {
                                    val mode = if (selectedMode == TaskMode.AUTO) inferTaskMode(text) else selectedMode
                                    draft = ""
                                    scope.launch { sendToBackend(text, mode) }
                                }
                            },
                            onDictate = { startDictation() },
                            onAttach = { fileLauncher.launch(arrayOf("*/*")) }
                        )
                        Destination.SETTINGS -> SettingsPage(
                            Modifier.padding(padding), backendUrl, appToken,
                            onUrl = { backendUrl = it }, onToken = { appToken = it },
                            onSave = {
                                val normalized = backendUrl.trim().trimEnd('/')
                                when {
                                    !normalized.startsWith("https://") -> { error = "أدخل عنوان الخادم كاملًا ويبدأ بـ https://، من دون /api/v1 في النهاية."; info = "" }
                                    appToken.isBlank() -> { error = "أدخل رمز الخادم المطابق لقيمة APP_ACCESS_TOKEN على الخادم."; info = "" }
                                    else -> { backendUrl = normalized; store.saveBackendUrl(normalized); store.saveAppToken(appToken.trim()); error = ""; info = "حُفظ عنوان الخادم ورمز الوصول على هذا الجهاز." }
                                }
                            },
                            info = info, error = error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Box(Modifier.size(if (compact) 34.dp else 48.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Purple, Cyan))), contentAlignment = Alignment.Center) {
            Text("F", color = White, fontSize = if (compact) 19.sp else 26.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(11.dp))
        Column { Text("FRATY AI", color = White, fontWeight = FontWeight.Bold, fontSize = if (compact) 14.sp else 18.sp); Text("IMAGINE · CREATE · BUILD", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp) }
    }
}

@Composable
private fun ChatPage(
    modifier: Modifier, messages: List<ChatMessage>, draft: String, onDraft: (String) -> Unit,
    busy: Boolean, error: String, info: String, selectedMode: TaskMode, onMode: (TaskMode) -> Unit,
    onSend: () -> Unit, onDictate: () -> Unit, onAttach: () -> Unit
) {
    var modeMenuExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (messages.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF211A3D), Panel, Color(0xFF10232F)))).padding(20.dp)) {
                Column {
                    Text("مرحبًا بك في FRATY AI", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text("منصة متقدمة للإبداع بالذكاء الاصطناعي", color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("ما الذي تود إنشاءه اليوم؟", color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text("اكتب طلبك كما هو. عند طلب تطبيق أو لصق كود Android، سيبدأ إنشاءه وبناء APK.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("ابدأ محادثة جديدة", color = Muted, fontSize = 12.sp)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false, contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(messages) { _, message -> MessageBubble(message) }
                if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = Cyan, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("جارٍ تنفيذ الطلب؛ قد يستغرق بناء APK عدة دقائق…", color = Muted, fontSize = 12.sp) } }
            }
        }
        AnimatedVisibility(error.isNotBlank()) { StatusCard(error, true) }
        AnimatedVisibility(info.isNotBlank()) { StatusCard(info, false) }
        if (messages.isEmpty()) Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.Start) {
                Text(if (selectedMode == TaskMode.AUTO) "المهمة: تلقائي — اختر من النقاط أو اكتب طلبك" else "المهمة: ${selectedMode.title}", color = Muted, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel2).padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp)) {
                IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, "اختيار ملف", tint = Muted) }
                BasicTextField(
                    value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 120.dp).padding(horizontal = 6.dp, vertical = 12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 15.sp),
                    decorationBox = { inner -> Box { if (draft.isEmpty()) Text("اكتب رسالتك…", color = Muted, fontSize = 14.sp); inner() } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() })
                )
                IconButton(onClick = onDictate) { Icon(Icons.Default.Mic, "إملاء صوتي", tint = Cyan) }
                Box {
                    IconButton(onClick = { modeMenuExpanded = true }) { Icon(Icons.Default.MoreVert, "أنواع المهام", tint = Cyan) }
                    DropdownMenu(expanded = modeMenuExpanded, onDismissRequest = { modeMenuExpanded = false }) {
                        TaskMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.title) },
                                leadingIcon = { Icon(mode.icon, null) },
                                onClick = { onMode(mode); modeMenuExpanded = false }
                            )
                        }
                    }
                }
                IconButton(onClick = onSend, enabled = !busy) { Icon(Icons.Default.Send, "إرسال", tint = if (busy) Muted else Purple) }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    val mediaUrl = Regex("https://\\S+").find(message.content)?.value
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.Start else Arrangement.End) {
        Column(Modifier.fillMaxWidth(.90f).clip(RoundedCornerShape(18.dp)).background(if (user) Panel2 else Color(0xFF17142A)).padding(13.dp)) {
            Text(if (user) "أنت" else "FRATY AI · خادم", color = if (user) Cyan else Color(0xFFB79CFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(message.content, color = White, fontSize = 14.sp, lineHeight = 21.sp)
            if (!mediaUrl.isNullOrBlank()) {
            Text("تنزيل الملف الناتج", color = Cyan, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).clickable { uriHandler.openUri(mediaUrl) })
            }
        }
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, url: String, token: String, onUrl: (String) -> Unit, onToken: (String) -> Unit, onSave: () -> Unit, info: String, error: String) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("إعداد الاتصال", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("أدخل عنوان HTTPS الأساسي لخادم FRATY، دون /api/v1. أدخل أيضًا رمز الخادم المطابق لـ APP_ACCESS_TOKEN. لا تضع مفتاح النموذج هنا.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        LabeledInput("عنوان HTTPS الأساسي", url, onUrl, "https://api.example.com", false)
        LabeledInput("رمز الخادم (مطلوب)", token, onToken, "APP_ACCESS_TOKEN الذي ضبطه مسؤول الخادم", true)
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("حفظ الإعدادات") }
        if (error.isNotBlank()) StatusCard(error, true)
        if (info.isNotBlank()) StatusCard(info, false)
        Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("حالة التكامل", color = Cyan, fontWeight = FontWeight.Bold)
                Text("المحادثة والبرمجة: POST /api/v1/chat (يتطلب مفتاح نموذج على الخادم).", color = Muted, fontSize = 12.sp)
                Text("إنشاء التطبيقات: يرسل الوصف أو كود Android إلى عامل بناء GitHub Actions ويعيد APK تجريبيًا داخل المحادثة.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("الصور والفيديو الحقيقيان غير مفعّلين. APK تجريبي لا يستخدم مفتاح توقيع متجر Play.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("الرمز هنا ليس مفتاح نموذج. يوصى بخادم HTTPS مع مصادقة ومعدلات استخدام محدودة.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun LabeledInput(label: String, value: String, onValue: (String) -> Unit, placeholder: String, secret: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = White, fontSize = 13.sp)
        OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), placeholder = { Text(placeholder, color = Muted) }, singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF343B49), focusedTextColor = White, unfocusedTextColor = White, cursorColor = Cyan))
    }
}

@Composable
private fun FeaturePage(modifier: Modifier, title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, prompt: String, onPrompt: (String) -> Unit, error: String, info: String, actionLabel: String, onAction: () -> Unit) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = Panel, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(62.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Purple, Cyan))), contentAlignment = Alignment.Center) { Icon(icon, null, tint = White, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.height(13.dp)); Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Text(description, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            }
        }
        OutlinedTextField(prompt, onPrompt, modifier = Modifier.fillMaxWidth().weight(1f, fill = false), minLines = 4, placeholder = { Text("صف طلبك…", color = Muted) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF343B49), focusedTextColor = White, unfocusedTextColor = White))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text(actionLabel) }
        if (error.isNotBlank()) StatusCard(error, true)
        if (info.isNotBlank()) StatusCard(info, false)
    }
}

@Composable
private fun NoticePage(modifier: Modifier, title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, info: String, onAction: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = Panel, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = Cyan, modifier = Modifier.size(48.dp)); Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp)); Text(description, color = Muted, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
            }
        }
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2)) { Text(if (title.contains("الصوت")) "تجربة إملاء النظام فقط" else "معلومات التهيئة", color = White) }
        if (info.isNotBlank()) StatusCard(info, false)
    }
}

@Composable
private fun StatusCard(text: String, error: Boolean) {
    Surface(color = if (error) Color(0xFF351B25) else Color(0xFF142B31), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (error) Icons.Default.Warning else Icons.Default.Info, null, tint = if (error) ErrorRed else Cyan, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(text, color = White, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun QuickChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = Color.White.copy(alpha = .08f), shape = RoundedCornerShape(30.dp)) { Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Cyan, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text(label, color = White, fontSize = 11.sp) } }
}

@Composable
private fun SmallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Panel, shape = RoundedCornerShape(24.dp)) { Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Cyan, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(label, color = Muted, fontSize = 11.sp) } }
}

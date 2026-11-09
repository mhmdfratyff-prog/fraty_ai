import { readFile } from 'node:fs/promises';
import { zipSync, strToU8 } from 'fflate';

const WRAPPER_DIR = new URL('./templates/android-compose/gradle/wrapper/', import.meta.url);
const WRAPPER_SCRIPT = new URL('./templates/android-compose/gradlew', import.meta.url);
const WRAPPER_BAT = new URL('./templates/android-compose/gradlew.bat', import.meta.url);

function safeText(value, fallback, maxLength) {
  return typeof value === 'string' && value.trim()
    ? value.trim().slice(0, maxLength)
    : fallback;
}

function xmlEscape(value) {
  return value.replace(/[&<>"']/g, ch => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
  })[ch]);
}

function normalizeKotlin(source) {
  let code = source.trim().replace(/^```(?:kotlin)?\s*/i, '').replace(/\s*```$/, '');
  if (!code.startsWith('package ')) code = `package com.fraty.generatedapp\n\n${code}`;
  else code = code.replace(/^package\s+[A-Za-z0-9_.]+\s*$/m, 'package com.fraty.generatedapp');
  if (!code.includes('class MainActivity') || !code.includes('setContent')) {
    throw Object.assign(new Error('تعذر إنشاء شاشة Android صالحة. جرّب وصفًا أبسط أو أعد الطلب.'), { status: 502 });
  }
  if (code.length > 350_000) {
    throw Object.assign(new Error('المشروع الناتج أكبر من الحد الآمن.'), { status: 502 });
  }
  return code;
}

function projectFiles({ appName, summary, mainActivity, wrapperJar, wrapperProperties, gradlew, gradlewBat }) {
  const escapedName = xmlEscape(appName);
  const readme = `# ${appName}\n\n${summary}\n\n## بناء APK\n\n1. افتح هذا المجلد في Android Studio وانتظر اكتمال Gradle Sync.\n2. اختر **Build > Build APK(s)**، أو نفّذ \`bash gradlew assembleDebug\` من الطرفية.\n3. ستجد APK الاختبار في \`app/build/outputs/apk/debug/app-debug.apk\`.\n\nهذا أرشيف مصدر، وليس APK جاهزًا. راجع الكود الناتج قبل تثبيت التطبيق أو استخدامه. يتطلب البناء Android SDK 35 واتصالًا لتنزيل اعتماديات Gradle أول مرة.\n`;
  const files = {
    'settings.gradle.kts': `pluginManagement {\n    repositories { google(); mavenCentral(); gradlePluginPortal() }\n}\n\ndependencyResolutionManagement {\n    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n    repositories { google(); mavenCentral() }\n}\n\nrootProject.name = "FratyGeneratedApp"\ninclude(":app")\n`,
    'build.gradle.kts': `plugins {\n    id("com.android.application") version "8.7.3" apply false\n    id("org.jetbrains.kotlin.android") version "2.0.21" apply false\n    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false\n}\n`,
    'gradle.properties': `org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nkotlin.code.style=official\n`,
    'app/build.gradle.kts': `plugins {\n    id("com.android.application")\n    id("org.jetbrains.kotlin.android")\n    id("org.jetbrains.kotlin.plugin.compose")\n}\n\nandroid {\n    namespace = "com.fraty.generatedapp"\n    compileSdk = 35\n    defaultConfig {\n        applicationId = "com.fraty.generatedapp"\n        minSdk = 26\n        targetSdk = 35\n        versionCode = 1\n        versionName = "1.0"\n    }\n    buildFeatures { compose = true }\n    compileOptions {\n        sourceCompatibility = JavaVersion.VERSION_17\n        targetCompatibility = JavaVersion.VERSION_17\n    }\n    kotlinOptions { jvmTarget = "17" }\n}\n\ndependencies {\n    implementation(platform("androidx.compose:compose-bom:2024.12.01"))\n    implementation("androidx.activity:activity-compose:1.10.0")\n    implementation("androidx.compose.ui:ui")\n    implementation("androidx.compose.ui:ui-graphics")\n    implementation("androidx.compose.ui:ui-tooling-preview")\n    implementation("androidx.compose.material3:material3")\n    implementation("androidx.compose.material:material-icons-extended")\n    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")\n    debugImplementation("androidx.compose.ui:ui-tooling")\n}\n`,
    'app/src/main/AndroidManifest.xml': `<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n    <application android:allowBackup="false" android:label="${escapedName}" android:supportsRtl="true" android:theme="@android:style/Theme.Material.Light.NoActionBar">\n        <activity android:name=".MainActivity" android:exported="true">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN" />\n                <category android:name="android.intent.category.LAUNCHER" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>\n`,
    'app/src/main/java/com/fraty/generatedapp/MainActivity.kt': mainActivity,
    'README.md': readme,
    'gradle/wrapper/gradle-wrapper.properties': wrapperProperties,
    'gradlew': gradlew,
    'gradlew.bat': gradlewBat,
    'gradle/wrapper/gradle-wrapper.jar': wrapperJar
  };
  return Object.fromEntries(Object.entries(files).map(([name, data]) => [
    name,
    typeof data === 'string' ? strToU8(data) : new Uint8Array(data)
  ]));
}

export async function generateAndroidProject({ prompt, apiKey, baseUrl, model }) {
  const systemPrompt = [
    'You are FRATY AI, an expert Android engineer. Turn the user request into a small, useful, self-contained Android starter app implemented with Kotlin and Jetpack Compose.',
    'Return ONLY a JSON object with exactly these string fields: appName, summary, mainActivity.',
    'mainActivity must be a complete compilable Kotlin file for package com.fraty.generatedapp with an android.app Activity named MainActivity and setContent { ... }.',
    'Use only Android SDK and Jetpack Compose Material3 APIs available from the standard dependencies in the project template. Include all imports. Do not add Gradle plugins, dependencies, manifest permissions, networking, file access, reflection, shell commands, or credentials.',
    'Create a working interactive prototype that implements the core request rather than echoing source code or describing steps. If the user provides code, adapt it into the Compose app instead of returning the same code unchanged.',
    'Use Arabic UI text when the user writes Arabic. Make the UI polished, responsive, accessible, and appropriate for right-to-left Arabic where applicable. Avoid external assets; use Compose shapes, color, typography, and icons from material-icons-extended.',
    'Keep the implementation concise enough to fit in one MainActivity.kt. Do not use Markdown fences.'
  ].join(' ');
  const apiRoot = (baseUrl || 'https://api.openai.com/v1').replace(/\/$/, '');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 75_000);
  let upstream;
  let response;
  try {
    upstream = await fetch(`${apiRoot}/chat/completions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${apiKey}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        model,
        messages: [
          { role: 'system', content: systemPrompt },
          { role: 'user', content: prompt }
        ],
        response_format: { type: 'json_object' },
        max_tokens: 8000,
        stream: false
      }),
      signal: controller.signal
    });
    response = await upstream.json().catch(() => ({}));
  } finally {
    clearTimeout(timer);
  }
  if (!upstream.ok) {
    console.error('Project generator provider returned HTTP', upstream.status, response?.error?.message || '');
    throw Object.assign(new Error('تعذر إنشاء المشروع الآن. حاول مرة أخرى بعد قليل.'), { status: 502 });
  }
  const content = response?.choices?.[0]?.message?.content;
  if (typeof content !== 'string' || !content.trim()) {
    throw Object.assign(new Error('لم يُرجع النموذج ملفات مشروع صالحة.'), { status: 502 });
  }
  let generated;
  try { generated = JSON.parse(content); }
  catch { throw Object.assign(new Error('استجابة إنشاء المشروع ليست JSON صالحًا. أعد المحاولة بوصف أبسط.'), { status: 502 }); }
  const appName = safeText(generated.appName, 'تطبيق FRATY', 80);
  const summary = safeText(generated.summary, 'مشروع Android أولي باستخدام Kotlin وJetpack Compose.', 1_000);
  const mainActivity = normalizeKotlin(generated.mainActivity || '');
  const [wrapperJar, wrapperProperties, gradlew, gradlewBat] = await Promise.all([
    readFile(new URL('gradle-wrapper.jar', WRAPPER_DIR)),
    readFile(new URL('gradle-wrapper.properties', WRAPPER_DIR), 'utf8'),
    readFile(WRAPPER_SCRIPT, 'utf8'),
    readFile(WRAPPER_BAT, 'utf8')
  ]);
  const archive = zipSync(projectFiles({ appName, summary, mainActivity, wrapperJar, wrapperProperties, gradlew, gradlewBat }), { level: 6 });
  if (archive.length > 12_000_000) {
    throw Object.assign(new Error('حجم المشروع الناتج تجاوز الحد المسموح.'), { status: 502 });
  }
  return { appName, summary, bytes: archive };
}

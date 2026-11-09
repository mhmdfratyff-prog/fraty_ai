import http from 'node:http';
import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { mkdir, readdir, readFile, stat, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { generateAndroidProject } from './project-generator.mjs';

const PORT = Number(process.env.PORT || 8080);
const MAX_BODY_BYTES = 1_000_000;
const WINDOW_MS = 60_000;
const MAX_REQUESTS_PER_WINDOW = Number(process.env.MAX_REQUESTS_PER_MINUTE || 30);
const MEDIA_DIR = path.resolve(process.env.MEDIA_DIR || './media');
const MEDIA_TTL_SECONDS = Number(process.env.MEDIA_TTL_SECONDS || 86_400);
const CHAT_API_KEY = process.env.GEMINI_API_KEY || process.env.OPENAI_API_KEY;
const counters = new Map();

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(body));
}

function constantTimeMatch(a, b) {
  const left = Buffer.from(a || '');
  const right = Buffer.from(b || '');
  return left.length === right.length && left.length > 0 && timingSafeEqual(left, right);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('Request body is too large.'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); }
      catch { reject(Object.assign(new Error('Invalid JSON body.'), { status: 400 })); }
    });
    req.on('error', reject);
  });
}

function rateLimit(req, res) {
  const key = req.socket.remoteAddress || 'unknown';
  const now = Date.now();
  const item = counters.get(key) || { start: now, count: 0 };
  if (now - item.start >= WINDOW_MS) { item.start = now; item.count = 0; }
  item.count += 1;
  counters.set(key, item);
  if (item.count > MAX_REQUESTS_PER_WINDOW) {
    json(res, 429, { error: 'Rate limit exceeded. Try again shortly.' });
    return false;
  }
  if (counters.size > 5_000) {
    for (const [k, v] of counters) if (now - v.start > WINDOW_MS * 2) counters.delete(k);
  }
  return true;
}

function authorize(req, res) {
  const expected = process.env.APP_ACCESS_TOKEN;
  if (!expected) {
    json(res, 503, { error: 'Backend is not configured: set APP_ACCESS_TOKEN on the server.' });
    return false;
  }
  if (!constantTimeMatch(req.headers['x-app-token'], expected)) {
    json(res, 401, { error: 'Missing or invalid application access token.' });
    return false;
  }
  return true;
}

function mediaSignature(id, expires) {
  return createHmac('sha256', process.env.APP_ACCESS_TOKEN || '').update(`${id}.${expires}`).digest('hex');
}

async function cleanupExpiredMedia() {
  await mkdir(MEDIA_DIR, { recursive: true, mode: 0o700 });
  const cutoff = Date.now() - MEDIA_TTL_SECONDS * 1000;
  for (const entry of await readdir(MEDIA_DIR, { withFileTypes: true })) {
    if (!entry.isFile()) continue;
    const file = path.join(MEDIA_DIR, entry.name);
    try { if ((await stat(file)).mtimeMs < cutoff) await unlink(file); } catch {}
  }
}

async function generateImage(prompt) {
  const apiKey = process.env.IMAGE_API_KEY || process.env.OPENAI_API_KEY;
  const model = process.env.IMAGE_MODEL || process.env.OPENAI_IMAGE_MODEL;
  const base = (process.env.IMAGE_API_BASE_URL || process.env.OPENAI_BASE_URL || 'https://api.openai.com/v1').replace(/\/$/, '');
  const publicBase = (process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL || '').replace(/\/$/, '');
  if (!apiKey || !model) throw Object.assign(new Error('مزوّد الصور غير مهيّأ. اضبط IMAGE_API_KEY وIMAGE_MODEL على الخادم.'), { status: 503 });
  if (!base.startsWith('https://') || !publicBase.startsWith('https://')) {
    throw Object.assign(new Error('يتطلب إنشاء الصور عنوان API وPUBLIC_BASE_URL عبر HTTPS على الخادم.'), { status: 503 });
  }

  const requestBody = { model, prompt, n: 1, size: process.env.IMAGE_SIZE || '1024x1024' };
  if (model.startsWith('gpt-image')) Object.assign(requestBody, { quality: process.env.IMAGE_QUALITY || 'medium', output_format: 'jpeg' });
  else requestBody.response_format = 'b64_json';

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 120_000);
  let providerResponse;
  let result;
  try {
    providerResponse = await fetch(`${base}/images/generations`, {
      method: 'POST',
      headers: { authorization: `Bearer ${apiKey}`, 'content-type': 'application/json' },
      body: JSON.stringify(requestBody),
      signal: controller.signal
    });
    result = await providerResponse.json().catch(() => ({}));
  } finally { clearTimeout(timer); }
  if (!providerResponse.ok) {
    console.error('Image provider returned HTTP', providerResponse.status, result?.error?.message || '');
    throw Object.assign(new Error('تعذّر على مزوّد الصور إكمال الطلب. راجع إعداد الموديل وحساب المزوّد.'), { status: 502 });
  }

  const image = result?.data?.[0];
  let bytes;
  let mime = model.startsWith('gpt-image') ? 'image/jpeg' : 'image/png';
  if (typeof image?.b64_json === 'string' && image.b64_json.length > 0) {
    bytes = Buffer.from(image.b64_json, 'base64');
    if (image.b64_json.length > 34_000_000) throw Object.assign(new Error('ملف الصورة الناتج أكبر من الحد المسموح.'), { status: 502 });
  } else if (typeof image?.url === 'string' && image.url.startsWith('https://')) {
    const download = await fetch(image.url, { signal: AbortSignal.timeout(30_000) });
    if (!download.ok) throw Object.assign(new Error('تعذّر تنزيل الصورة من المزوّد.'), { status: 502 });
    mime = (download.headers.get('content-type') || '').split(';')[0];
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(mime)) throw Object.assign(new Error('صيغة الصورة التي أعادها المزوّد غير مدعومة.'), { status: 502 });
    bytes = Buffer.from(await download.arrayBuffer());
    if (bytes.length > 25_000_000) throw Object.assign(new Error('ملف الصورة الناتج أكبر من الحد المسموح.'), { status: 502 });
  } else {
    throw Object.assign(new Error('استجابة مزوّد الصور لا تتضمن صورة قابلة للعرض.'), { status: 502 });
  }
  if (!bytes.length) throw Object.assign(new Error('ملف الصورة الناتج فارغ.'), { status: 502 });

  await cleanupExpiredMedia();
  const id = randomUUID();
  const ext = mime === 'image/png' ? 'png' : mime === 'image/webp' ? 'webp' : 'jpg';
  await writeFile(path.join(MEDIA_DIR, `${id}.${ext}`), bytes, { mode: 0o600 });
  const expires = Math.floor(Date.now() / 1000) + MEDIA_TTL_SECONDS;
  const signature = mediaSignature(id, expires);
  const imageUrl = `${publicBase}/api/v1/media/${id}.${ext}?expires=${expires}&signature=${signature}`;
  return { message: image.revised_prompt ? `تم إنشاء الصورة. الوصف المنقّح: ${image.revised_prompt}` : 'تم إنشاء الصورة.', url: imageUrl };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { ok: true, service: 'fraty-ai-backend' });
  if (req.method === 'GET' && url.pathname === '/api/v1/integrations') {
    return json(res, 200, {
      chat: Boolean(CHAT_API_KEY && process.env.OPENAI_MODEL),
      projectSource: Boolean(CHAT_API_KEY && process.env.OPENAI_MODEL),
      image: Boolean((process.env.IMAGE_API_KEY || process.env.OPENAI_API_KEY) && (process.env.IMAGE_MODEL || process.env.OPENAI_IMAGE_MODEL) && (process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL)?.startsWith('https://')),
      video: false,
      realtimeVoice: false,
      googleAuth: false,
      screenAnalysis: false,
      fileAnalysis: false
    });
  }
  const mediaMatch = /^\/api\/v1\/media\/([a-f0-9-]{36})\.(jpg|png|webp|zip)$/.exec(url.pathname);
  if (req.method === 'GET' && mediaMatch) {
    if (!rateLimit(req, res)) return;
    const expires = Number(url.searchParams.get('expires'));
    const signature = url.searchParams.get('signature') || '';
    const now = Math.floor(Date.now() / 1000);
    if (!Number.isSafeInteger(expires) || expires < now || expires > now + MEDIA_TTL_SECONDS || !constantTimeMatch(signature, mediaSignature(mediaMatch[1], expires))) {
      return json(res, 403, { error: 'رابط الوسائط غير صالح أو انتهت صلاحيته.' });
    }
    try {
      const bytes = await readFile(path.join(MEDIA_DIR, `${mediaMatch[1]}.${mediaMatch[2]}`));
      const contentType = mediaMatch[2] === 'zip' ? 'application/zip' : mediaMatch[2] === 'png' ? 'image/png' : mediaMatch[2] === 'webp' ? 'image/webp' : 'image/jpeg';
      const headers = { 'content-type': contentType, 'cache-control': 'private, max-age=300', 'x-content-type-options': 'nosniff' };
      if (mediaMatch[2] === 'zip') headers['content-disposition'] = 'attachment; filename="fraty-android-project.zip"';
      res.writeHead(200, headers);
      return res.end(bytes);
    } catch { return json(res, 404, { error: 'انتهت صلاحية الصورة أو لم تعد موجودة.' }); }
  }
  if (req.method !== 'POST') return json(res, 404, { error: 'Not found.' });
  if (!rateLimit(req, res)) return;
  if (!authorize(req, res)) return;

  if (url.pathname === '/api/v1/images') {
    try {
      const body = await readJson(req);
      if (typeof body.prompt !== 'string' || !body.prompt.trim() || body.prompt.length > 32_000) {
        return json(res, 400, { error: 'أدخل وصفًا للصورة لا يتجاوز 32,000 حرف.' });
      }
      return json(res, 200, await generateImage(body.prompt.trim()));
    } catch (error) {
      const status = error.status || (error.name === 'AbortError' ? 504 : 500);
      console.error('Image request failed:', error.message);
      return json(res, status, { error: status === 504 ? 'انتهت مهلة إنشاء الصورة.' : error.message || 'تعذر إنشاء الصورة.' });
    }
  }
  if (url.pathname === '/api/v1/projects') {
    try {
      const body = await readJson(req);
      if (typeof body.prompt !== 'string' || !body.prompt.trim() || body.prompt.length > 16_000) {
        return json(res, 400, { error: 'اكتب وصف التطبيق أو ألصق كوده بحد أقصى 16,000 حرف.' });
      }
      if (!CHAT_API_KEY || !process.env.OPENAI_MODEL) {
        return json(res, 503, { error: 'توليد مشاريع Android غير مضبوط على الخادم.' });
      }
      const publicBase = (process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL || '').replace(/\/$/, '');
      if (!publicBase.startsWith('https://')) {
        return json(res, 503, { error: 'الخادم لا يملك عنوان HTTPS عامًا لحفظ المشروع مؤقتًا.' });
      }
      const generated = await generateAndroidProject({
        prompt: body.prompt.trim(),
        apiKey: CHAT_API_KEY,
        baseUrl: process.env.OPENAI_BASE_URL,
        model: process.env.OPENAI_MODEL
      });
      await cleanupExpiredMedia();
      const id = randomUUID();
      await writeFile(path.join(MEDIA_DIR, `${id}.zip`), generated.bytes, { mode: 0o600 });
      const expires = Math.floor(Date.now() / 1000) + MEDIA_TTL_SECONDS;
      const signature = mediaSignature(id, expires);
      const projectUrl = `${publicBase}/api/v1/media/${id}.zip?expires=${expires}&signature=${signature}`;
      return json(res, 200, {
        message: `تم تجهيز مشروع «${generated.appName}» كملف ZIP. نزّله وافتحه في Android Studio؛ هذه ملفات مصدر وليست APK جاهزًا.`,
        url: projectUrl
      });
    } catch (error) {
      const status = error.status || (error.name === 'AbortError' ? 504 : 500);
      console.error('Project request failed:', error.message);
      return json(res, status, { error: error.message || 'تعذر إنشاء مشروع Android.' });
    }
  }
  if (url.pathname === '/api/v1/videos') {
    return json(res, 503, { error: 'مزوّد الفيديو لم يحدد بعد. اختر مزوّدًا ثم أضف تكامله وإعداداته على الخادم.' });
  }
  if (url.pathname !== '/api/v1/chat') return json(res, 404, { error: 'Not found.' });
  if (!CHAT_API_KEY) return json(res, 503, { error: 'Chat provider is not configured: set GEMINI_API_KEY or OPENAI_API_KEY on the backend.' });

  try {
    const body = await readJson(req);
    if (!Array.isArray(body.messages) || body.messages.length === 0 || body.messages.length > 50) {
      return json(res, 400, { error: 'messages must be a non-empty array with at most 50 items.' });
    }
    const messages = body.messages.map(({ role, content }) => {
      if (!['user', 'assistant', 'system'].includes(role) || typeof content !== 'string' || content.length > 50_000) {
        throw Object.assign(new Error('Each message needs a valid role and content under 50,000 characters.'), { status: 400 });
      }
      return { role, content };
    });
    const base = (process.env.OPENAI_BASE_URL || 'https://api.openai.com/v1').replace(/\/$/, '');
    const model = process.env.OPENAI_MODEL;
    if (!model) return json(res, 503, { error: 'Chat provider is not configured: set OPENAI_MODEL on the backend.' });
    if (!base.startsWith('https://')) return json(res, 503, { error: 'OPENAI_BASE_URL must use HTTPS.' });
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 55_000);
    let upstream;
    let response;
    try {
      upstream = await fetch(`${base}/chat/completions`, {
        method: 'POST',
        headers: { authorization: `Bearer ${CHAT_API_KEY}`, 'content-type': 'application/json' },
        body: JSON.stringify({ model, messages, stream: false }),
        signal: controller.signal
      });
      response = await upstream.json().catch(() => ({}));
    } finally { clearTimeout(timer); }
    if (!upstream.ok) {
      console.error('Provider returned HTTP', upstream.status, response?.error?.message || '');
      return json(res, 502, { error: 'The configured chat provider returned an error. Check server logs and provider settings.' });
    }
    const message = response?.choices?.[0]?.message?.content;
    if (typeof message !== 'string' || !message.trim()) return json(res, 502, { error: 'Provider response did not contain assistant text.' });
    return json(res, 200, { message, model: response.model || model });
  } catch (error) {
    const status = error.status || (error.name === 'AbortError' ? 504 : 500);
    console.error('Chat request failed:', error.message);
    return json(res, status, { error: status === 504 ? 'The provider request timed out.' : error.message || 'Internal server error.' });
  }
});

server.listen(PORT, '0.0.0.0', () => console.log(`FRATY AI backend listening on port ${PORT}`));

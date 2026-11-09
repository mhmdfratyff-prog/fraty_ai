import { randomUUID } from 'node:crypto';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { generateAndroidProject } from './project-generator.mjs';

const MEDIA_DIR = path.resolve(process.env.MEDIA_DIR || './media');
const REPOSITORY = process.env.BUILD_GITHUB_REPOSITORY || 'mhmdfratyff-prog/fraty_ai';
const WORKFLOW = process.env.BUILD_GITHUB_WORKFLOW || 'build-generated-android.yml';
const REF = process.env.BUILD_GITHUB_REF || 'main';
const API_VERSION = '2026-03-10';
const BUILD_ID_RE = /^[a-f0-9-]{36}$/;

export function validBuildId(value) {
  return typeof value === 'string' && BUILD_ID_RE.test(value);
}

function fileFor(id, suffix) {
  if (!validBuildId(id)) throw Object.assign(new Error('معرّف البناء غير صالح.'), { status: 400 });
  return path.join(MEDIA_DIR, `${id}${suffix}`);
}

function requireWorkerSecret() {
  if (!process.env.BUILD_WORKER_SECRET) {
    throw Object.assign(new Error('عامل البناء غير مضبوط على الخادم.'), { status: 503 });
  }
}

function requireGitHubToken() {
  if (!process.env.BUILD_GITHUB_TOKEN) {
    throw Object.assign(new Error('لم يتم ربط صلاحية GitHub المطلوبة لبدء البناء.'), { status: 503 });
  }
}

async function githubRequest(route, init = {}) {
  requireGitHubToken();
  const response = await fetch(`https://api.github.com/repos/${REPOSITORY}/${route}`, {
    ...init,
    headers: {
      accept: 'application/vnd.github+json',
      authorization: `Bearer ${process.env.BUILD_GITHUB_TOKEN}`,
      'X-GitHub-Api-Version': API_VERSION,
      ...(init.headers || {})
    },
    signal: init.signal || AbortSignal.timeout(25_000)
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error('GitHub build API returned HTTP', response.status, body?.message || '');
    throw Object.assign(new Error(response.status === 401 || response.status === 403
      ? 'صلاحية GitHub غير صالحة أو لا تملك إذن Actions لهذا المستودع.'
      : 'تعذر الاتصال بخدمة بناء GitHub.'), { status: response.status === 401 || response.status === 403 ? 503 : 502 });
  }
  return body;
}

async function readJob(id) {
  try { return JSON.parse(await readFile(fileFor(id, '.job.json'), 'utf8')); }
  catch (error) {
    if (error.code === 'ENOENT') throw Object.assign(new Error('وظيفة البناء غير موجودة أو انتهت مدة الاحتفاظ بها.'), { status: 404 });
    throw error;
  }
}

async function writeJob(job) {
  await writeFile(fileFor(job.id, '.job.json'), JSON.stringify(job), { mode: 0o600 });
}

export async function startAndroidBuild({ prompt, apiKey, baseUrl, model }) {
  requireWorkerSecret();
  requireGitHubToken();
  const id = randomUUID();
  const generated = await generateAndroidProject({ prompt, apiKey, baseUrl, model, buildId: id });
  await mkdir(MEDIA_DIR, { recursive: true, mode: 0o700 });
  await writeFile(fileFor(id, '.source.zip'), generated.bytes, { mode: 0o600 });
  const job = { id, appName: generated.appName, createdAt: new Date().toISOString(), runId: null };
  await writeJob(job);
  try {
    const dispatched = await githubRequest(`actions/workflows/${encodeURIComponent(WORKFLOW)}/dispatches`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ref: REF, inputs: { build_id: id } })
    });
    job.runId = dispatched.workflow_run_id || null;
    job.runUrl = dispatched.html_url || null;
    await writeJob(job);
  } catch (error) {
    console.error('Could not dispatch APK build:', error.message);
    throw error;
  }
  return { jobId: id, appName: generated.appName };
}

export async function readBuildSource(id) {
  requireWorkerSecret();
  await readJob(id);
  try { return await readFile(fileFor(id, '.source.zip')); }
  catch (error) {
    if (error.code === 'ENOENT') throw Object.assign(new Error('ملفات البناء المؤقتة غير موجودة.'), { status: 404 });
    throw error;
  }
}

export async function saveBuildApk(id, bytes) {
  requireWorkerSecret();
  await readJob(id);
  if (!Buffer.isBuffer(bytes) || bytes.length < 4 || bytes.length > 45_000_000 || bytes.subarray(0, 4).toString('hex') !== '504b0304') {
    throw Object.assign(new Error('ملف APK الذي أرسله عامل البناء غير صالح أو يتجاوز الحد.'), { status: 400 });
  }
  await writeFile(fileFor(id, '.apk'), bytes, { mode: 0o600 });
  return { ok: true };
}

async function findRun(job) {
  const result = await githubRequest(`actions/workflows/${encodeURIComponent(WORKFLOW)}/runs?branch=${encodeURIComponent(REF)}&event=workflow_dispatch&per_page=25`);
  const runs = Array.isArray(result.workflow_runs) ? result.workflow_runs : [];
  return runs.find(run => String(run.display_title || run.name || '').includes(job.id)) || null;
}

export async function getAndroidBuildStatus(id, { publicBase, expires, sign }) {
  const job = await readJob(id);
  try {
    await stat(fileFor(id, '.apk'));
    const signature = sign(id, expires);
    return {
      state: 'completed',
      appName: job.appName,
      message: `اكتمل بناء APK التجريبي «${job.appName}».`,
      url: `${publicBase}/api/v1/media/${id}.apk?expires=${expires}&signature=${signature}`
    };
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (Date.now() - Date.parse(job.createdAt) > 20 * 60_000) {
    return { state: 'failed', appName: job.appName, error: 'انتهت مهلة بناء APK. حاول مرة أخرى بوصف أبسط.' };
  }
  let run = null;
  if (job.runId) {
    try { run = await githubRequest(`actions/runs/${job.runId}`); }
    catch (error) {
      if (error.status !== 404) throw error;
    }
  }
  if (!run) run = await findRun(job);
  if (!run) return { state: 'queued', appName: job.appName };
  if (!job.runId && run.id) {
    job.runId = run.id;
    job.runUrl = run.html_url || null;
    await writeJob(job);
  }
  if (run.status === 'completed') {
    if (run.conclusion === 'success') {
      return { state: 'failed', appName: job.appName, error: 'انتهى البناء دون وصول APK إلى التطبيق. أعد المحاولة أو تواصل مع الدعم.' };
    }
    return { state: 'failed', appName: job.appName, error: 'تعذّر بناء التطبيق. راجع طلبك وحاول مرة أخرى.' };
  }
  return { state: run.status === 'in_progress' ? 'building' : 'queued', appName: job.appName };
}

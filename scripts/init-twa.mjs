#!/usr/bin/env node
/**
 * 免交互生成 TWA（Trusted Web Activity）Android 工程
 * ------------------------------------------------------------------
 * 为什么不用 `bubblewrap init`：
 *   bubblewrap CLI 的 init 走 inquirer 交互层（必须有真 TTY），CI 里无法自动化；
 *   就算用 pty 硬驱，init 在发现签名密钥不存在时还会追加 7 个密码类问题，极易卡死。
 *   所以这里直接调用官方底层库 @bubblewrap/core 的 TwaManifest + TwaGenerator，
 *   一次性把完整 Gradle 工程吐出来，全程零交互。
 *
 * 产物：
 *   twa/twa-manifest.json      —— TWA 清单（与 bubblewrap build 兼容）
 *   twa/settings.gradle        —— Gradle 工程
 *   twa/build.gradle
 *   twa/gradlew (+ wrapper)
 *   twa/app/...                —— Android App 模块（含 LauncherActivity / 图标 / web_app_manifest.json）
 *
 * 之后由工作流直接跑 `./gradlew assembleRelease` + zipalign + apksigner 出签名 APK。
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { TwaManifest, TwaGenerator, ConsoleLog, fetchUtils } = require('@bubblewrap/core');

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'twa');

/* ------------------------------------------------------------------ *
 * 壳配置：改这里 = 改 App 外壳（图标/名称/主题色/起始页）
 * 改课程内容不需要重打 APK —— TWA 加载线上站点自动生效。
 * ------------------------------------------------------------------ */
const CFG = {
  packageId: 'com.xiangshao.grade3',
  name: '英语小花园',
  launcherName: '英语小花园',
  host: 'suike.app.workbuddy.host',
  // 相对路径（必须以 / 开头）；TWA 启动 URL = https://host + startUrl
  startUrl: '/?sharecode=24NRJSLYLH',
  scope: 'https://suike.app.workbuddy.host/',
  themeColor: '#4CAF6E',
  navigationColor: '#4CAF6E',
  backgroundColor: '#FDF8EE',
  orientation: 'portrait',
  // androidbrowserhelper 2.7.4 的 minSdk 是 24，必须 >= 24
  minSdkVersion: 24,
  appVersion: '1.0.0',
  appVersionCode: 1,
  // 图标与 web manifest 都走 raw.githubusercontent（稳定、免鉴权、Content-Type 正确）
  // 可用环境变量覆盖（便于本地/离线验证）：
  //   TWA_ICON_URL / TWA_MASKABLE_ICON_URL / TWA_WEB_MANIFEST_URL
  iconUrl: process.env.TWA_ICON_URL
    || 'https://raw.githubusercontent.com/xiyun788-ux/xiangshao-twa/main/icons/icon-512.png',
  maskableIconUrl: process.env.TWA_MASKABLE_ICON_URL
    || process.env.TWA_ICON_URL
    || 'https://raw.githubusercontent.com/xiyun788-ux/xiangshao-twa/main/icons/icon-512.png',
  webManifestUrl: process.env.TWA_WEB_MANIFEST_URL
    || 'https://raw.githubusercontent.com/xiyun788-ux/xiangshao-twa/main/manifest.webmanifest',
  keystorePath: path.join(ROOT, 'android.keystore'),
  keystoreAlias: 'xiangshao',
};

function log(...args) {
  console.log('[init-twa]', ...args);
}

/**
 * 把「公网 URL → 仓库内本地文件」映射成拦截器。
 * core 生成工程时必须下载图标和 web manifest；这里直接喂本地文件，
 * 让整个生成步骤**零网络依赖**（raw.githubusercontent 在部分网络下会被重置连接）。
 * 注意：URL 本身保持不变，App 内元数据（webManifestUrl）仍指向公网地址。
 */
function installLocalAssetInterceptor() {
  const localAssets = new Map();
  const candidates = [
    { url: CFG.webManifestUrl, file: path.join(ROOT, 'manifest.webmanifest'), type: 'application/manifest+json' },
    { url: CFG.iconUrl, file: path.join(ROOT, 'icons/icon-512.png'), type: 'image/png' },
  ];
  for (const c of candidates) {
    if (c.url && fs.existsSync(c.file)) localAssets.set(c.url, c);
  }
  if (!localAssets.size) return;

  fetchUtils.setFetchEngine('node-fetch');
  const realFetch = fetchUtils.fetch.bind(fetchUtils);
  fetchUtils.fetch = async (input) => {
    const url = String(input);
    const asset = localAssets.get(url);
    if (!asset) return realFetch(url);
    const buf = await fsp.readFile(asset.file);
    return new Response(buf, { status: 200, headers: { 'content-type': asset.type } });
  };
  log(`已启用本地资源直读（${[...localAssets.keys()].length} 个），生成过程不依赖外网`);
}

async function main() {
  // 1) 清理上次生成的目录，保证幂等
  await fsp.rm(OUT, { recursive: true, force: true });
  await fsp.mkdir(OUT, { recursive: true });

  // 2) 构造 TWA 清单
  const manifest = new TwaManifest({
    packageId: CFG.packageId,
    host: CFG.host,
    name: CFG.name,
    launcherName: CFG.launcherName,
    display: 'standalone',
    themeColor: CFG.themeColor,
    themeColorDark: CFG.themeColor,
    navigationColor: CFG.navigationColor,
    navigationColorDark: CFG.navigationColor,
    backgroundColor: CFG.backgroundColor,
    enableNotifications: false,
    startUrl: CFG.startUrl,
    iconUrl: CFG.iconUrl,
    maskableIconUrl: CFG.maskableIconUrl,
    splashScreenFadeOutDuration: 300,
    signingKey: { path: CFG.keystorePath, alias: CFG.keystoreAlias },
    appVersion: CFG.appVersion,
    appVersionCode: CFG.appVersionCode,
    webManifestUrl: CFG.webManifestUrl,
    fullScopeUrl: CFG.scope,
    minSdkVersion: CFG.minSdkVersion,
    orientation: CFG.orientation,
    generatorApp: 'xiangshao-twa',
    fallbackType: 'customtabs',
    enableSiteSettingsShortcut: false,
  });

  const err = manifest.validate();
  if (err) {
    throw new Error(`TWA 清单校验失败: ${err}`);
  }

  await manifest.saveToFile(path.join(OUT, 'twa-manifest.json'));
  log('twa-manifest.json 已写入');

  // 3) 生成 Android 工程（会下载图标、写入 web_app_manifest.json）
  //    优先从仓库本地文件读取图标/manifest，避免外网抖动；拿不到时自动回落到真实网络。
  installLocalAssetInterceptor();
  const consoleLog = new ConsoleLog('init-twa');
  await new TwaGenerator().createTwaProject(OUT, manifest, consoleLog, (p, t) => {
    log(`生成进度 ${p}/${t}`);
  });
  log('Android 工程已生成');

  // 4) 模板里用的是 jcenter()（已废弃），换成 mavenCentral() 避免解析失败
  const buildGradle = path.join(OUT, 'build.gradle');
  let src = await fsp.readFile(buildGradle, 'utf8');
  if (src.includes('jcenter()')) {
    src = src.replace(/jcenter\(\)/g, 'mavenCentral()');
    await fsp.writeFile(buildGradle, src, 'utf8');
    log('build.gradle: jcenter() -> mavenCentral()');
  }

  // 5) 写 manifest-checksum.txt（sha1），让后续若有人跑 `bubblewrap build` 也不会弹更新确认
  const manifestRaw = await fsp.readFile(path.join(OUT, 'twa-manifest.json'));
  const sha1 = (await import('node:crypto')).createHash('sha1').update(manifestRaw).digest('hex');
  await fsp.writeFile(path.join(OUT, 'manifest-checksum.txt'), sha1, 'utf8');

  // 6) 自检：关键文件必须都在
  const required = [
    'settings.gradle',
    'build.gradle',
    'gradlew',
    'gradle/wrapper/gradle-wrapper.jar',
    'app/build.gradle',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/res/values/strings.xml',
    'app/src/main/res/raw/web_app_manifest.json',
    `app/src/main/java/${CFG.packageId.split('.').join('/')}/LauncherActivity.java`,
    'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png',
    'app/src/main/res/mipmap-xxxhdpi/ic_maskable.png',
  ];
  const missing = [];
  for (const rel of required) {
    if (!fs.existsSync(path.join(OUT, rel))) missing.push(rel);
  }
  if (missing.length) {
    throw new Error(`生成结果缺少文件:\n  - ${missing.join('\n  - ')}`);
  }
  log('自检通过，关键文件齐全');
}

main().catch((e) => {
  console.error('[init-twa] 失败:', e && e.stack ? e.stack : e);
  process.exit(1);
});

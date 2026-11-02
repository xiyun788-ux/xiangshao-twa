/**
 * public/sw.js —— Service Worker（离线可用）
 * ---------------------------------------------------------------------------
 * 为什么需要：手机上把它「添加到主屏幕」当 App 用时，最怕的是**到了没网的地方
 * （车上、公园、外婆家）打开一片空白**，孩子直接就不用了。
 * 这个 SW 把应用外壳 + 静态资源（尤其 512 个音频）缓存下来，**断网也能继续学、继续发音**。
 *
 * 缓存策略：
 *   · 导航请求（打开页面）→ 网络优先，失败回退缓存的 index.html（保证离线能开）
 *   · /static/ 与 /assets/ 资源 → 缓存优先 + 后台静默更新（秒开，且下次是新版本）
 *   · 其它同源 GET → 网络优先
 *
 * ⚠️ 版本号改动即可让旧缓存整体失效（activate 时清掉非当前版本的缓存）。
 *
 * ────────────────────────────────────────────────────────────────────
 * 访问凭证（sharecode）
 * ────────────────────────────────────────────────────────────────────
 * 「分享链接型」的静态托管要求**每个请求**都带 `?sharecode=xxx`，不带就 403。
 * 而 SW 注册 URL 上的 query **不会**自动传给后续 fetch，所以这里自己取：
 * `self.location.href` 就是 SW 脚本的 URL，注册时带了 query → 这里能读出来。
 *
 * 于是 SW 发出的每一个请求都会补上凭证，缓存的 key 也用「补过凭证的 URL」，
 * 保证前端请求（不带 query）→ SW 改写（带 query）→ 命中同一个缓存条目。
 * 换到正常托管时页面上没有 sharecode → SHARE_CODE 为空 → 全部原样放行。
 */

/** 缓存版本：改动即可让旧缓存整体失效 */
const CACHE_VERSION = 'xiangshao-v1'

/** 访问凭证（分享链接型托管用；正常托管下为空串） */
const SHARE_CODE = (() => {
  try {
    return new URL(self.location.href).searchParams.get('sharecode') || ''
  } catch {
    return ''
  }
})()

/** 给 URL 补上访问凭证 */
function withShare(input) {
  if (!SHARE_CODE) return input
  try {
    const u = new URL(input, self.location.href)
    if (!u.searchParams.has('sharecode')) u.searchParams.set('sharecode', SHARE_CODE)
    return u.href
  } catch {
    return input
  }
}

/** 应用外壳：装的时候先缓存，离线时靠它们把应用拉起来 */
const CORE_ASSETS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './apple-touch-icon.png',
]

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE_VERSION)
      // 单个资源 404 不该让整次安装失败 → 逐个 add，失败就跳过
      .then((cache) => Promise.allSettled(CORE_ASSETS.map((url) => cache.add(withShare(url)))))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k))),
      )
      .then(() => self.clients.claim()),
  )
})

/** 是否是可长期缓存的静态资源（音频 / 字体 / 图标 / 插画 / 打包产物） */
function isStaticAsset(url) {
  return (
    url.pathname.includes('/static/') ||
    url.pathname.includes('/assets/') ||
    url.pathname.endsWith('.mp3') ||
    url.pathname.endsWith('.woff2') ||
    url.pathname.endsWith('.svg') ||
    url.pathname.endsWith('.png')
  )
}

/**
 * 找出命中该请求的缓存名。
 * caches.match() 默认**跨所有缓存**搜索，所以同一个文件可能落在不同缓存里
 * （SW 自己的 + 用户显式下载的）。要避免重复占用空间，就得知道它在哪。
 */
async function findCacheName(request) {
  const names = await caches.keys()
  for (const name of names) {
    const c = await caches.open(name)
    if (await c.match(request)) return name
  }
  return null
}

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return

  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return

  // ① 导航：缓存优先（秒开），同时后台静默更新
  //    ⚠️ 为什么不用「网络优先」：
  //      断网时 fetch **不是立刻失败**，而是会卡住等网络超时（可能十几秒）。
  //      网络优先 = 页面在没信号的地方一直白屏 —— 而 PWA 的全部价值就是
  //      「到了没网的地方照样能用」。所以改成缓存优先 + 后台更新。
  //    hash 路由下所有页面的导航 URL 都是同一个（hash 不参与 HTTP 请求），
  //    所以缓存一条 index.html 就够了。
  if (req.mode === 'navigate') {
    const target = withShare(req.url)
    event.respondWith(
      (async () => {
        const hit = await caches.match(target)
        if (hit) {
          // 有缓存 → 立刻返回（断网也能秒开），后台静默更新
          fetch(target)
            .then(async (res) => {
              if (res && res.ok) {
                const c = await caches.open(CACHE_VERSION)
                await c.put(target, res.clone())
              }
            })
            .catch(() => {})
          return hit
        }
        // 缓存里没有 → 走网络并缓存；断网则回退到 index.html
        try {
          const res = await fetch(target)
          if (res && res.ok) {
            const c = await caches.open(CACHE_VERSION)
            await c.put(target, res.clone())
          }
          return res
        } catch {
          const fallback = await caches.match(withShare('./index.html'))
          return fallback || Response.error()
        }
      })(),
    )
    return
  }

  // ② 静态资源：缓存优先（秒开），同时后台更新
  //    ⚠️ 后台更新必须**写回「命中来源的那个缓存」**，不能无条件写进 SW 自己的缓存。
  //    否则同一份文件会在两个缓存里各存一份：
  //      · SW 自己的 'xiangshao-v1'
  //      · 用户点「下载全部发音」时前端 put 的缓存
  //    结果就是 512 条音频在手机里占 13MB（多一倍），实测踩到过。
  if (isStaticAsset(url)) {
    event.respondWith(
      (async () => {
        // 统一改写成「带凭证的 URL」，缓存的 key 也用它
        const target = withShare(req.url)
        // 找出命中该请求的缓存（缓存可能有多个）
        const cacheName = await findCacheName(target)
        if (cacheName) {
          const c = await caches.open(cacheName)
          const hit = await c.match(target)
          if (hit) {
            // 后台静默更新：写回同一个缓存，保持「一份」
            fetch(target)
              .then(async (res) => {
                if (res && res.ok) await c.put(target, res.clone())
              })
              .catch(() => {})
            return hit
          }
        }
        // 没命中：走网络并存进 SW 自己的缓存
        const res = await fetch(target)
        if (res && res.ok) {
          const c = await caches.open(CACHE_VERSION)
          await c.put(target, res.clone())
        }
        return res
      })(),
    )
    return
  }

  // ③ 其它同源请求：网络优先
  const target = withShare(req.url)
  event.respondWith(
    fetch(target).catch(() => caches.match(target).then((hit) => hit || Response.error())),
  )
})

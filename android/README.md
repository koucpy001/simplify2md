# simplify2md Android 端

原生 Kotlin + WebView 外壳，复用 `mdview/frontend` 的同一份 Vue 应用；渲染/编辑逻辑不重写，
平台差异收敛在 `@bridge` 垫片与本文档描述的绑定语义内。

```
android/
  app/src/main/java/io/github/koucpy001/simplify2md/
    MainActivity.kt          WebView 宿主（WebViewAssetLoader，安全上下文）
    bridge/                  requestId Promise 协议（传输层，纯 JVM 可测）
    binding/                 桌面独有绑定的 Android 语义（本文件对应 todo 7）
    web/                     asset URL → APK asset 映射
```

## 无桌面对应物的绑定语义（映射表）

桌面版这五个绑定由 Go 后端实现（`mdview/app.go`），Android 是单 Activity WebView 应用，
语义不同。以下为**钉死的契约**，由计划 todo 7 与 JVM 单测共同约束。

| @bridge 绑定 | Android 语义 | 理由 | 剩余工作归属 |
|---|---|---|---|
| `SetTitle(t)` | 空操作（no-op）：忽略参数，不产生任何状态 | Android 无窗口标题；工具栏已显示文件名与脏标记 `●`（`App.vue:1321`）。**不发明标题栏** | 无（特此固定；`App.vue` 的 `updateTitle()` 调用保持不变） |
| `SetStartupArgs` | **不进 JS 桥**：Kotlin 不导出、`BridgeMethods.WHITELIST` 不含此名 | 桌面由 Go `main.go:23` 调用，用于捕获进程启动参数；Android 的对应物是 launch Intent，由冷启动路径处理 | 无（Intent 解析与热启动派发归 todo 18） |
| `GetStartupFile()` | `Promise<string>`：返回**首个**冷启动文件类 URI，缺失时为 `""`，**consume-once**（第二次调用返回 `""`）。Kotlin **直接返回 URI 字符串**，**禁止** `{type,value}` 对象 | 与桌面 `mdview/app.go:90-98` 的 `Promise<string>` 契约完全一致；纯文本一律走 `mdview:open-text`，两条数据通道必须形状单一 | Intent 采集与 `ACTION_SEND` 派发归 todo 18；本 todo 交付 `StartupFileGate` + 处理器注册 |
| `SetDirty(b)` | 仅**记录**脏标志（`DirtyFlag`，`AtomicBoolean`） | 桌面由 Go `beforeClose` 读取以拦截关窗；Android 无窗口关闭钩子，等价物是返回键守卫 | 返回键守卫本身归 **todo 18**；本 todo 只提供可读的标志，**不自建状态机**替代前端既有守卫（`App.vue:1378-1380`） |
| `ConfirmExit()` | 结束当前 Activity：`finish()`（经 `runOnUiThread`，因为处理器运行在 `Dispatchers.IO`） | 桌面 `ConfirmExit` 调用 `runtime.Quit`；Android 对应 `Activity.finish()` | 触发 `mdview:confirm-exit` 的返回键接线归 **todo 18**；本 todo 只实现 `finish()` 的薄胶水 |

处理器实现在 `binding/DesktopOnlyBindings.kt`，纯逻辑在 `binding/StartupFileGate.kt` 与
`binding/DirtyFlag.kt`；`registerOn(bridge)` 经 `Bridge.registerHandler` 注册，而后者以
`BridgeMethods.WHITELIST` 做前置断言（`require(isKnown)`），因此不会扩大分派面。

## Intent 排队规则（todo 18）

冷启动与热启动走**两条互斥的通道**，同一 URI 绝不重复派发（单一交付规则，评审 H1/NEW-1）：

1. **冷启动文件类 intent**（`ACTION_VIEW` / `ACTION_EDIT` 的 `data` URI，或 `ACTION_SEND` +
   `EXTRA_STREAM`）**不进队列**：在 `MainActivity.onCreate` 经 `IntentRouter` 判定后记入
   `StartupFileGate`，由前端 `GetStartupFile` consume-once 取走（第二次为 `""`）。
   文件 URI 只接受 `content:` / `file:` scheme；`javascript:` 等一律忽略。
2. **冷启动纯文本 intent**（`ACTION_SEND` + `EXTRA_TEXT`，无 STREAM）由 Kotlin 侧
   `StartupTextBuffer` **单独缓冲**，并在 bridge-ready（即 JS 完成事件注册、消费完
   `GetStartupFile`、启动恢复 settle、草稿恢复弹窗 settle 之后）经 `mdview:open-text`
   **一次性派发**——不缓冲则首次"分享文本到应用"会静默丢失。
3. **热启动 intent**（`onNewIntent` 到达，先 `setIntent`）**进入事件队列**：由
   `ReadyEventQueue` 在 ready 前入队、`markReady()` 时按到达顺序重放；**ready 后每次入队
   都立即派发**（`ReadyImmediateDispatchTest` 锁定，防止热启动 intent 滞留队列）。
4. **同一 intent 同时带 `EXTRA_STREAM` 与 `EXTRA_TEXT` 时，`EXTRA_STREAM` 优先、
   `EXTRA_TEXT` 忽略**（写死优先级，禁止两载体同时投递）。
5. `ACTION_EDIT` 与 `ACTION_VIEW` **同处理**，不做区分（编辑/预览由前端视图模式决定）。
6. `ACTION_SEND` 纯文本经**事件** `mdview:open-text` 交付（payload 为文本），`App.vue`
   以 `filePath=''`、`currentName=''`、`readonly=false` 载入并标记 dirty，且**必须走与
   `open-path` 相同的 `requestSwitch` 脏文档守卫**——绝不直接覆盖未保存内容。
7. 返回键守卫（`BackKeyGuard`）：有未保存改动 → `mdview:confirm-exit` 走前端既有守卫；
   干净 → `finish()`。API 33+ 经 `OnBackInvokedCallback`（默认优先级，预测性返回不会绕过
   守卫自动退出），API <33 走 `onBackPressed` 兜底。

Intent 解析为纯 JVM 逻辑（`binding/IntentRouter.kt` + `IntentRouterTest` 逐格覆盖交付矩阵；
`binding/StartupTextBuffer.kt`、`binding/BackKeyGuard.kt` 各有 JVM 单测），`MainActivity`
只做 payload 抽取与路由应用。

## `@bridge` 导出符号核对

两平台导出集合必须严格相等，共 **20** 个运行时符号（类型导出不计入）：

- **17** 个 App 函数：`OpenFile`、`SaveFile`、`PickSavePath`、`LoadImageForSrc`、`ReadFileAt`、
  `GetRecents`、`GetStartupFile`、`RemoveRecent`、`SetDirty`、`SetTitle`、`ConfirmExit`、
  `CheckForUpdate`、`SaveDraft`、`LoadDraft`、`ListDrafts`、`ClearDraft`、`ClearRecents`
- **+1** `EventsOn`（JS 本地事件注册表）
- **+1** `BrowserOpenURL`（同样经 `__bridgeCall` 分派，但计入 runtime 符号）
- **+1** `notifyBridgeReady`（独立 `__bridgeReady` 接口方法；桌面为 no-op）

`17 + 1 + 1 + 1 = 20`。`SetStartupArgs` 由 Go 导出但前端从未使用，**刻意不在** JS 桥与
`BridgeMethods.WHITELIST` 中。Kotlin 侧 `BridgeMethods.WHITELIST` 为 **18** 个可经
`__bridgeCall` 分派的名字（17 个 App 函数 + `BrowserOpenURL`），断言见
`android/app/src/test/.../BridgeMethodsTest.kt`。

## 外部链接策略（`BrowserOpenURL`）

页面可传入任意字符串，因此按**不可信输入**处理（`binding/ExternalLinks.kt`，纯 JVM）：

1. 空白 / 无 scheme / scheme 语法非法 → `Invalid`，**不启动任何 Activity**。
2. scheme 不在 `http` / `https` / `mailto` 白名单（含 `javascript:`、`intent:`、`file:`、`data:`、
   `foo://bar` 等）→ `Blocked`，**不启动任何 Activity**（这就是"无 handler 的 scheme 不得崩溃"的兜底之一）。
3. 白名单 scheme → `Intent.ACTION_VIEW`；若系统无对应 handler（如未装浏览器），
   `ActivityNotFoundException` 被捕获并折算为 `NoHandler`，应用**不崩溃**。
4. `BrowserOpenURL` 与桌面一致是 **fire-and-forget**：处理器恒返回 `null`（JS 侧 `void`），
   `Blocked` / `NoHandler` 只记录并 resolve，**不 reject**，因此不会产生未处理的 Promise 拒绝。

注册经 `Bridge.registerHandler(BridgeMethods.BROWSER_OPEN_URL)`，受 `WHITELIST` 前置断言约束；
`WebViewClient.shouldOverrideUrlLoading` 的非资产导航复用同一策略，避免两条路径行为漂移。

## 事件契约（Kotlin 发射 ↔ `App.vue` 监听）

事件名冻结在 `bridge/BridgeEvents.kt`（`BridgeEventsTest` 逐字锁定），发射入口是 `bridge/AppEvents.kt`，
其 emitter lambda 由 `MainActivity` 接到 `Bridge.emitEvent`（ready 前入队、`markReady()` 后按序重放）。

| Kotlin 常量 | 发射方法 | payload | `App.vue` 监听点 | 触发归属 |
|---|---|---|---|---|
| `BridgeEvents.CONFIRM_EXIT` = `mdview:confirm-exit` | `AppEvents.confirmExit()` | `null` | `bridge-events.ts` | 返回键 → **todo 18** |
| `BridgeEvents.OPEN_PATH` = `mdview:open-path` | `AppEvents.openPath(uri)` | JSON 字符串（文档 URI） | `bridge-events.ts` | `onNewIntent` / `ACTION_SEND` → **todo 18** |
| `BridgeEvents.FILE_CHANGED` = `mdview:file-changed` | `AppEvents.fileChanged()` | `null` | `bridge-events.ts` | 前台刷新（**todo 19，已实现**） |
| `BridgeEvents.IME` = `mdview:ime` | `AppEvents.ime(heightPx)` | `{"height":<int>}`（物理像素） | `bridge-events.ts` | 根布局 inset 监听（**todo 17**，已实现） |

前端的事件注册统一在 `mdview/frontend/src/lib/bridge-events.ts`（`registerBridgeEvents`，五个处理器
一次性注册；`mdview:ime` 只在此处注册，`App.vue` 不得重复注册），并导出 `createStartupReadyGate()`
就绪门：草稿恢复弹窗未决时不发 `notifyBridgeReady`，两个 onMounted 出口各至多一次 ready。
`mdview:open-text`（分享纯文本 → 未命名文档）的处理器同样在 `bridge-events.ts` 注册（todo 17），
其 `requestSwitch` 守卫语义与 Kotlin 路由由 todo 18 补齐；`BridgeEvents.REGISTERED` 刻意不含它。

## IME 与边到边（todo 17，单一机制）

target 36 下 `adjustResize` 已失效，本工程**只用一种机制**，三者缺一不可且互斥：

1. `WindowCompat.setDecorFitsSystemWindows(window, false)`（`MainActivity.onCreate`）；
2. 根布局 `ViewCompat.setOnApplyWindowInsetsListener` 同时消费 `systemBars()` 的 top+bottom 与
   `ime()` 的 bottom：top 作为 WebView 容器 paddingTop（工具栏是页面首个元素，否则被状态栏/刘海
   遮挡），bottom 取 `max(systemBars.bottom, ime.bottom)` 作为容器 paddingBottom；inset 整体
   `CONSUMED`；键盘高度经 `mdview:ime` 报告（API <30 无 `ime()` inset，报告 0，键盘直接遮挡属已知限制）；
3. Manifest `windowSoftInputMode="adjustNothing"`；viewport meta **不含** `interactive-widget`。

前端把高度写入 `--mdview-ime-height` CSS 变量（`imeInsetPx` 纯函数：0/负/NaN/缺失 → `0px`，
超大值钳制到 10000px），并在非 composing 期触发 `scrollIntoView` 兜底；`cm-editor.ts` 的
composing 期跳过逻辑未改动。另有 WebView 最低版本门（`WebViewCompat.getCurrentWebViewPackage`，
`ime/WebViewMinVersion.kt`，阈值 major ≥ 90）：低于阈值或无法判定时以 Toast 提示输入可能异常。

## 发布签名与版本注入（todo 21）

Release APK 的签名材料**只经环境变量注入**（CI 中来自 GitHub Secrets，仓库零密钥），
`android/app/build.gradle.kts` 读取以下 4 个环境变量名：

- `ANDROID_KEYSTORE_PATH`（CI 由 `ANDROID_KEYSTORE_BASE64` 解码到临时文件后传入路径）
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

签名判别机制（写死）：

| 场景 | 行为 |
|---|---|
| 材料缺失 + 分支/PR 构建 | 跳过签名，打印明确 warning，产出 **unsigned** APK |
| 材料缺失 + `-PrequireSigning=true` | **构建直接失败**，绝不产出 unsigned 产物 |
| 材料齐全 | `signingConfigs.release` 生效，产出已签名 APK |

版本注入：仅当显式传 `-PappVersionName` **或** `GITHUB_REF_TYPE == 'tag'` 时才解析版本；
其余情况（分支/PR）在解析**之前**回退占位值 `0.0.0` / versionCode `1`，分支名绝不当作版本解析。
`versionName` 为去掉 `v` 前缀的 tag（保留后缀，如 `0.3.0-rc1`）；`versionCode = major*1000000 +
minor*10000 + patch*100 + channel`（正式版 channel=99，预发布取后缀末尾整数，范围 1–98），
保证同一版本的正式版 versionCode 严格大于其任何预发布，可顺序覆盖安装。

## 构建与测试

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=384m" \
  -Pkotlin.compiler.execution.strategy=in-process --console=plain
./gradlew --stop
```

## 验证

发布前的可重复验证协议：先跑本机门禁，再做真机与桌面复测。手机基准视口 **360×800**、桌面 **1280×800**；
断言值均为移动化改造后的实测基线（Chromium 复现），真机与 WebView2 以同一张表逐项核对。

### 本机门禁

前端（`mdview/frontend`，逐条期望 `exit 0`）：

```bash
npm run build                  # 含 vue-tsc 类型检查
npx tsx test-mobile-ui.ts      # 期望 "MOBILE UI OK: 11/11 cases hold."
npx tsx test-bridge-parity.ts  # 两平台 @bridge 导出集合严格相等（20 个运行时符号）
# 其余 10 个脚本同样逐个运行、逐个 exit 0：
# test-pipeline / test-token-mapping / test-save-policy / test-paths / test-draft-binding /
# test-bridge-events / test-ime-helper / test-open-text / test-rel-link / test-web-env
```

Android（`android/`）：门禁即上节「构建与测试」给出的命令（`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`）；
有签名材料时把 `:app:assembleDebug` 换成 `:app:assembleRelease`（材料缺失 + `-PrequireSigning=true` 必须 FAIL，属设计门禁）。
期望：Kotlin 单测 0 失败（基线规模 342 个 `@Test`）、lint 0 error（8 个 warning 为在册基线，不应新增）、产出对应 APK。

### 手机端断言（360×800）

| 断言 | 期望值 |
|---|---|
| 默认视图 | **预览**（不是分屏）；正文可用宽度 ≥ **300dp**（移动内边距 `12px 14px` 下实测 **332dp**） |
| 工具栏 | **单行**，高约 **80dp**（按钮行 48 + gap 5 + 信息行 18 + padding 8）；六项控件 `打开 / 保存 / 查找 / 编辑 / 预览 / ⋯` 全部在屏内 |
| 大纲 | **覆盖式抽屉**（遮罩存在、正文宽度不变，抽屉宽 280dp）；竖↔横旋转后无残留；横屏按桌面方式停靠 **220dp** |
| 状态行最坏情形 | `.status` ≥ **72dp**（长编码 / 长文件名 / 长字数 / 长状态串下由 `.stats` 省略号让位），状态行整体无溢出 |
| 触摸目标 | 全部按钮 ≥ **48×48dp** |
| 页面溢出 | `documentElement.scrollWidth === innerWidth` 且工具栏 `scrollWidth === clientWidth` |

> 注意：出现最近文件后，工具栏会多出最近文件 `<select>` 自己的 48dp 行（工具栏 133dp / 3 行）；
> 上表的 80dp 单行断言针对无最近记录的新装状态，不因该行而失败。

### 手机端 GUI 复测清单（8 条）

| # | 步骤 → 期望 |
|---|---|
| 1 | 全新安装或清数据后首次启动 → 默认进入 **预览**（不是分屏），正文可用宽度 ≥ 300dp |
| 2 | 工具栏为 **单行**（约 80dp），六项控件 `打开 / 保存 / 查找 / 编辑 / 预览 / ⋯` 全部在屏内可点 |
| 3 | 点 `⋯` → 菜单**恰为** `另存为 / 大纲 / 暗色 / 检查更新`，逐项生效（暗色即刻切换；检查更新有可见结果） |
| 4 | 打开 `大纲` → 正文宽度**不变**（覆盖式抽屉 + 遮罩，不挤压内容）；点条目跳转后抽屉自动关闭 |
| 5 | 手机竖屏 ↔ 横屏往返旋转 → 无残留遮罩/抽屉；横屏下大纲按桌面方式停靠（220dp） |
| 6 | 打开含宽表格的文档 → 表格**自身**横向滚动、不被裁剪；页面本身不出现横向溢出 |
| 7 | 代码块的 `复制` 按钮在触屏上**常显**且可点（成功则提示"已复制"，被拒则"复制失败"） |
| 8 | `查找` 栏停在屏幕底部、完整可点（"上一个 / 下一个 / 关闭"不被裁切） |

### `[device]` 真机清单（按区域）

⭐ 为发布验收重点项。

| 区域 | 步骤 → 期望 |
|---|---|
| 启动与基础 | 冷启动 → 页面加载成功且 `window.isSecureContext === true`，非白屏 |
| 打开·保存·取消 | 打开 `.md` → 可编辑并保存；取消文件选择 → 当前文档不丢；桥 echo 返回正确 JSON |
| 外部链接与事件 | 预览中点击文档里的外部链接 → 经**系统浏览器**打开（不白屏、不崩溃、不留在应用内）；打开文件与外部修改时，状态栏或日志能看到对应事件到达 |
| 只读文档另存为 | 打开云盘/只读来源 → 保存弹"另存为"；**另存为成功后再次保存不再弹窗**；打开 `.md` 后杀进程重启，可从最近文件直接重开 |
| 最近文件 | ⭐ 下拉列表显示**可读文件名**（如 `论文.md`）而非 `content://` 原文，且可重开；把已记录文档在外部删除后重开 → 条目自动移除且不崩溃 |
| 草稿恢复 | 编辑约 35s 后强杀 → 重启提示恢复且内容还原 |
| 编码与换行 | GB18030 与 Big5 文档真机往返不乱码（JVM 通过不蕴含真机通过）；⭐ **字节保真文档**中键入非 Latin-1 字符（中文/emoji）→ 弹"另存为 UTF-8" → 确认后以 UTF-8 另存，且 dirty 保持到保存成功 |
| 保存中崩溃恢复 | ⭐ 保存过程中强杀 → 重启出现**原生三选一恢复弹窗**，默认项与长度判定一致（实际长度 ≥ expectedLen 默认"保留当前内容"；< 则默认"用备份恢复"）；指纹不可得时**不得删除备份**（只提示）；journal 无法解析时备份保留、journal 改名 .corrupt 并提示（绝不删备份） |
| 图片树授权 | 本地存储含 `images/` 子目录的文档 → 图片正常显示；从 `Download/` 打开 → 提示而非白图；云盘来源 → "不支持"占位而非空白 |
| IME 与边到边 | 编辑态聚焦末行输入 → 光标行始终可见（留截图）；分屏同样成立；收起键盘无残留空白；工具栏不被状态栏/刘海遮挡 |
| 返回键守卫 | 有未保存改动按返回 → 前端守卫弹窗；"不保存退出"后该文件的草稿不再提示 |
| 文件关联与单实例 | 前台/后台从文件管理器连续打开两个不同 `.md` → 复用同一实例并加载；⭐ **在 ≥2 个不同文件管理器中确认应用出现在 `.md` 打开方式列表**；用 ACTION_EDIT（"编辑"）打开同样生效 |
| 分享 | 从其他应用分享纯文本 → 以未命名文档载入（走脏文档守卫）；分享文件 → 按 open-path 路由 |
| 外部变更前台刷新 | 外部修改当前文档后切回前台 → 无本地改动时内容刷新；有未保存改动时弹窗询问而非覆盖（Android 无实时检测，属如实降级） |
| 剪贴板与环境 | `isSecureContext` / `localStorage` / 代码块复制 / 相对链接打开 四项通过；剪贴板被拒时界面显示"复制失败" |
| 更新检查 | 启动自动检查与手动"检查更新" → 有新版时弹出提示，点击经系统浏览器打开下载页 |
| 触摸可达 | 无硬件键盘、仅触摸完成 打开→编辑→保存→查找→切换视图→退出；旋转后工具栏与内容不重叠 |
| 签名安装 | 安装已签名的 release 产物成功；证书非 debug（`release.yml` 内建 `apksigner` 门禁在 tag 上会拒绝 debug 证书） |

### 桌面 WebView2 复测（零回归）

在 Windows 上构建桌面版后，于 **1280×800** 逐项核对：

| 检查 | 期望值 |
|---|---|
| 工具栏 | **41dp / 单行 / 10 个按钮**，顺序：打开 保存 另存为 查找 编辑 分屏 预览 大纲 暗色 检查更新 |
| `.statusline` | `display: contents`，不产生可见盒（0×0）；文件名 / 编码 / 字数 / 状态与按钮同一行渲染 |
| 大纲（展开） | 停靠式展开（非抽屉、无遮罩），宽 **220px**，正文相应变窄 |
| `.preview` 内边距 | `20px 28px` |
| 分屏 | 编辑 / 预览双栏与滚动同步正常（**手机端隐藏分屏，桌面必须保留**） |
| 编辑与快捷键 | `Ctrl+O` / `Ctrl+S` / `Ctrl+Shift+S` / `Ctrl+F` / `F3` / `Esc` 行为与改动前一致 |

### 记录要求

每条判据记录 **设备型号 / Android 版本 / APK 来源（本地构建或哪个 tag）/ 结果**；截图或 logcat 片段随发布证据归档。
**不得**用"代码看起来对"冒充真机通过。

## 最近文件模型（todo 12）

最近文件以 JSON 数组存于 `filesDir/config.json`，每条为
`{uri, displayName, lastOpened, readonly}`（`storage/AppConfig.kt`）：

- `uri` 是**不透明句柄**（Android 为 SAF 文档 URI，桌面为路径），只用于路由，**绝不**解析其末段当文件名；
- `displayName` 在 Android 来自 `OpenableColumns.DISPLAY_NAME`（桌面 shim 用 `baseName(path)`）。opaque provider 的 URI 末段
  （如 `content://…/document/msf%3A1000000123`）是垃圾，因此列表项的名称为空时也只回退到 `baseName`，不会假造文件名；
- 列表按最近优先，上限 10（对齐桌面 `mdview/app.go:recordRecent`），写入使用同目录临时文件 + rename；
- 文件损坏 / 缺字段 / 旧版"裸字符串数组"形状都能容忍：解析失败一律视为空列表（不阻塞启动），缺 `uri` 的条目丢弃，
  裸字符串升级为 `displayName` 为空的条目。

`GetRecents()` 在 `@bridge` 层统一返回 `RecentEntryLike[]`（`{id, name}`）：`id` 即 `uri`，`name` 即 `displayName`。

**授权释放规则**（`storage/RecentsStore.kt`，全部由 JVM 单测锁定）：

- `RemoveRecent(uri)` / `ClearRecents()` 会调用 `releasePersistableUriPermission`，但**绝不释放当前正打开文档的授权**
  ——否则会话中途丢失写权限，保存 / 前台刷新 / 图片解析全部失效；
- 清空时跳过当前 URI；仅在条目被真正移除（显式删除或超出上限被淘汰）时释放；
- 释放失败（provider 无可持久授权、或条目是桌面路径）被吞掉，绝不影响列表变更；配置写失败同样不影响打开/保存。

前端路径工具 `mdview/frontend/src/lib/paths.ts` 提供平台中立的 `baseName` / `dirOf`；`displayPath` 具名 computed
（`currentName || baseName(filePath) || '未标题.md'`）是工具栏、另存为默认名、状态栏与"文件已被修改"弹窗的唯一名称来源，
因此界面不会出现 percent-encoded URI 原文。草稿恢复的草稿↔文档绑定由 `src/lib/draft-binding.ts` 的纯函数判定：
命名草稿仅当 `sha1(uri)` 与当前文档一致才绑定，否则按未命名草稿恢复并清空 `filePath`（防止把草稿覆盖写回启动恢复的文件）。

## 草稿存储与草稿键身份（todo 14）

草稿由 `storage/DraftStore.kt`（纯 JVM，可测）按 `mdview/app.go:736-840` 逐条移植，
文件系统边界抽象为 `DraftFileSystem`，Android 实现见 `storage/AndroidDrafts.kt`。

- **位置**：`filesDir/autosave/<key>.md`（app-private，目录按需 `mkdirs`）。**绝不**写入 `cacheDir`
  （系统可回收）、SAF 树、同步目录或 config 目录；不使用 `os.UserConfigDir` 语义。
- **键身份**：`DraftKey.of(uri)` = `sha1(uriString)` 的 **40 位小写十六进制**；无文档 URI 时为字面量
  `untitled`。派生方式与前端 `App.vue:451-461`（`crypto.subtle` SHA-1 over UTF-8）**完全一致**，
  因此跨会话稳定；显示名或任何用户文本**绝不**进入键，键是唯一到达文件系统的字符串。
- **键校验**：正则 `^[0-9a-f]{40}$|^untitled$`（`DraftStore.KEY_PATTERN`，对应 `mdview/app.go:77`）。
  大写十六进制、39/41 位、空串、含 `/`、`\`、`..` 等一律在任何 IO 之前拒绝（JS Promise reject，
  磁盘无任何写入），因此路径穿越不可达。

| 绑定 | 语义 | 对应 Go |
|---|---|---|
| `SaveDraft(key, content)` | 写 `<key>.md`（首次保存时建目录） | `SaveDraft`（`:752-767`） |
| `LoadDraft(key)` | 读内容；缺失/非法键 reject | `LoadDraft`（`:810-823`） |
| `ListDrafts()` | 返回 `[{key, modTime}]`，modTime 为 **epoch 秒**（同 Go `Unix()`），按 modTime **倒序**；跳过目录、非 `.md` 与非法键项；目录缺失视为空列表 | `ListDrafts`（`:771-806`） |
| `ClearDraft(key)` | 删除 `<key>.md`；键不存在为 no-op（幂等） | `ClearDraft`（`:827-840`） |

modTime 相等时以 **key 升序**作为确定性 tiebreak（Go 的 `sort.Slice` 此时顺序未定义，测试会因此
flaky）；这不改变"倒序"契约。四个绑定均经 `Bridge.registerHandler` 注册，名字早已在
`BridgeMethods.WHITELIST` 中，**不新增桥函数、不新增事件**。

**放弃后不复活**：`ClearDraft` 真正删除文件；前端 `abandonDraftFor`（`App.vue:518-535`）另外用
`draftAbandonToken` 取消该键的定时器与在途写入，使迟到的 `SaveDraft` 无法重建草稿。JVM 单测断言
`clear` 后 `load` reject 且不再出现在 `ListDrafts` 中。

## 保存的可靠性（todo 11）

Android 的 SAF 文档 URI **无法**做"临时文件 + rename 覆盖"，因此桌面 `README.md:18` 的文件级
承诺仅适用于 Windows；**Android 不提供原子替换**，只提供"写入失败回滚 + 启动对账恢复
（状态机 + 全量哈希）"。两者是不同的可靠性模型，不得混称。

保存流程（`storage/SaveStore.kt`）：

0. **写入门禁**（`SaveBindings`，移植桌面 `mdview/app.go:222` 的 `canWrite` 白名单）：
   只读文档（provider 无 `FLAG_SUPPORTS_WRITE`）在进入状态机**之前**就以
   `path not permitted`（与 Go 错误串逐字一致）拒绝。没有这道门禁，对只读文档的保存会先写好
   backup + journal（期望哈希是**新内容**）再在 `openWrite` 失败，journal 被刻意保留，
   下次启动对账发现 `writing` 状态哈希不匹配，弹出**误导性的三选一恢复弹窗**；

1. 先在内存完成全部编码与换行转换（`encoding/Encoding.kt`）；
2. 从目标 URI 读出**当前磁盘字节**（不是编辑器内存内容），复制到 `filesDir/backup/<hash>.bak`
   （`<hash> = SHA-256(目标 URI 字符串)`；用 `filesDir` 而**非** `cacheDir`，后者可能被系统回收）；
3. 写 journal `filesDir/backup/<hash>.json`（状态 `writing`、`expectedLen`、**全量** `expectedSha256`；
   不使用部分/三段校验）；
4. 通过 `ContentResolver.openFileDescriptor(uri, "wt")` 写入，`flush()` 后 `FileDescriptor.sync()`
   （best-effort）；回退到 `"w"` 时必须显式 `FileChannel.truncate(encoded.size)`（`"w"` 不保证截断，
   短写会残留旧尾部）；
5. 立即回读目标并计算实际哈希/长度，把 journal **原子改写**为状态 `written`（记 observed hash/len），
   再删除备份与 journal。

备份或 journal 写入失败 → 保存**直接中止**，绝不触及目标。写入/回读/长度异常 → 用备份回写并向 UI
报错（绝不静默）；回读本身抛错同样视为写失败。`writing` 与 `written` 是**两个不同状态**：写入失败时
journal 保留，交给下次启动对账。

启动对账（`storage/ReconcileEngine.kt`，在创建 WebView **之前**执行）：

| journal 状态 | 目标校验 | 行为 |
|---|---|---|
| `written` | — | 判定完成，静默清理 journal 与备份 |
| `writing` | 全量哈希一致 | 判定完成，静默清理 |
| `writing` | 缺失 / 长度或哈希不一致 | **原生 `AlertDialog` 三选一**，绝不自动覆盖或回滚 |
| 任意 | 指纹不可得 | 不删除备份，提示用户 |
| 无法解析 | — | 不删除备份；journal 重命名为 `.corrupt` 并提示 |
| 无对应 journal 的 `.bak` | — | 孤儿备份，记录并提示，不自动删除 |

三选一与默认项（决策逻辑为纯函数 `ReconcileDecision`，JVM 单测覆盖）：

| 选项 | 语义 |
|---|---|
| 保留当前内容 | 保持目标现状，删除 journal 与备份 |
| 用备份恢复 | 用备份回写目标（回写后再校验一次） |
| 两者都保留 | 保持目标现状，把备份改名为 `<hash>.kept` |

默认项**只按长度**判定：仅当实际长度 ≥ `expectedLen` 时默认"保留当前内容"；实际长度 < `expectedLen`
（疑似截断）时默认"用备份恢复"；目标缺失时默认"用备份恢复"。弹窗不可取消，未点选不会应用任何选择。

## 相对图片解析（todo 13）

Android 没有"文件所在目录"概念，相对图片（`![](images/foo.png)`）依赖用户对所在文件夹的
**一次性树授权**（`ACTION_OPEN_DOCUMENT_TREE`）。`LoadImageForSrc(src, mdUri, imageRoot)`
的判定矩阵（`image/ImageResolver.kt`，纯 JVM 可测）：

| 分支 | 条件 | 行为 |
|---|---|---|
| (a) | `http(s):` / `data:` / `blob:` 绝对 URL | WebView 直接加载，不进桥 |
| (b) | 相对路径 + 已授权树 | `DocumentsContract.buildDocumentUriUsingTree` 沿相对段构造子文档 URI，经本机 `/media/<token>` 端点流式返回 |
| (c) | 相对路径 + 未授权树 | **挂起**等待一次树授权（每文档去重只弹一次）；授权成功 → 该文档所有待决图片一次性解析；取消 → 全部以 `image-tree-cancelled` 拒绝，本会话内不再重复弹窗 |
| (d) | 树外（含 `../`）、系统禁止授权目录（存储根 / `Download/` / `Android/data` / `Android/obb`）、provider 不透明、无文档 URI（未标题 / `documentId` 不可解析）、front matter `imageRoot` 为 Windows 绝对路径 | 显式失败占位 + 状态栏提示，绝不显示空白图 |
| (e) | 成功路径 | 返回 `{url, mime}`（token 端点），**不是 base64**；超过 **6MB** 走占位 |

**关键限制（必须如实声明）**：分支 (b) 的树内相对解析**仅对"路径型 documentId"的 provider
（本地 `ExternalStorageProvider`）有效**。云盘等 provider 的 documentId 是不透明句柄，
对其追加路径段是猜测——**不得假装支持**，一律走 (d) 的"不支持"占位。

**persisted-tree 关联规则**：启动时从 `contentResolver.persistedUriPermissions` 筛出树型授权，
用 `DocumentsContract.getTreeDocumentId(treeUri)` 与 `DocumentsContract.getDocumentId(docUri)`
取各自 documentId，按 `docId == treeDocId || docId.startsWith(treeDocId + "/")` 判定归属，
多候选取**最长 treeDocId**。**禁止**用 `…/document/…` 与 `…/tree/…` 的裸 URI 前缀比较——
文档 URI 不含 `/tree/` 段，裸前缀匹配在"先打开文件再显示图片"的常规路径上永远失败。

**跨文档失效**：pending 队列与前端 `hydrateImage` 都带每文档 generation token；`applyLoaded`
（文档切换）递增 generation 并丢弃上一 generation 的待决请求，迟到的旧结果不会按 lazy id +
lazySrc 匹配到新文档节点（串图 / 缓存污染）。

**树授权名额**：树授权同样 `takePersistableUriPermission`（try/catch 降级为会话级并提示），
按 LRU 上限（≤8）管理，超限时释放最旧且**非当前激活**的树，避免触碰系统持久授权名额上限
（旧版 128 / API 30+ 512）后抛 `SecurityException`。

**失败令牌**（前端 `lib/image-token.ts` 映射为中文状态栏提示，`test-token-mapping.ts` 断言）：
`image-not-in-tree` / `image-unsupported-provider` / `image-too-large` / `image-denied` /
`image-tree-cancelled`。映射只做**信息性**提示，不含"重试"动作——恢复由 (c) 的挂起/恢复流程驱动。

## 更新检查（todo 15）

移植 `mdview/app.go:629-726`。纯逻辑在 `update/`（JVM 可测），网络边界抽象为 `UpdateHttpClient`，测试注入假实现、**不触网**：

- 端点 `https://api.github.com/repos/koucpy001/simplify2md/releases/latest`，请求头 `User-Agent: simplify2md` 与 `Accept: application/vnd.github+json`（`update/UpdateChecker.kt`）；
- semver 比较逐条对齐 Go：去掉单个前导 `v`、缺 minor/patch 视为 0、第四段起忽略（`1.2.3.4` == `1.2.3`）、非数字段 / 空段 / 溢出段一律解析失败；
- **畸形 tag / 非法版本一律视为"无更新"而非错误**（`UpdateInfo.NONE`），不会打扰用户；非 200 或 JSON 非法则抛 `UpdateCheckException`（自动检查静默、手动检查可见）；
- `html_url` 仅当以 `https://github.com/koucpy001/simplify2md/releases` 开头才保留，否则置空——任意（甚至恶意）URL 都不可能到达打开器；前端 `BrowserOpenURL` 再经 `ExternalLinks` 白名单二次兜底；
- 运行版本取 `BuildConfig.VERSION_NAME`；为 `"dev"` 时**短路且零网络调用**；
- 网络用 `HttpURLConnection`（无第三方 HTTP 依赖），**显式 5s connect / 5s read timeout**（禁止无限等待）；调用只在 `Dispatchers.IO` 上执行，无轮询、无主线程网络；
- 前端保持"提示 → `BrowserOpenURL` 打开下载页"（`App.vue:73-119`），不申请安装权限、不静默安装、不解析 APK 元数据；
- 本地 `versionName` 目前是占位 `0.0.0`（todo 21 由 tag 注入）；调试构建会因此把任何 release 视为"有更新"，属预期。

## 前台刷新替代文件监视（todo 19，明确语义）

**Android 没有实时外部变更检测。** 这是相对桌面 fsnotify 监视的**如实降级**：SAF 文档 URI 无法
监听，计划也**禁止轮询与后台线程**。等价物是"每次回到前台（`Activity.onResume`）评估一次"的策略
（`storage/ForegroundRefresh.kt`，纯 JVM 可测；IO 抽象为 `CurrentDocument` + `ForegroundRefreshIo`）：

| 条件 | 行为 | 说明 |
|---|---|---|
| 未打开文档（未标题 / URI 为空） | 什么都不做 | `NO_DOCUMENT` |
| 落在**自写窗口**内（τ=500ms，同桌面 `mdview/app.go:226`） | 什么都不做 | 刚保存完的回前台**不**算外部变更（`SELF_WRITE`） |
| `dirty == false` 且 URI 仍可读 | 重读文档 + 发 `mdview:file-changed` | 前端既有处理器在非脏时静默 `loadPath` 重载，界面内容刷新、无弹窗（`RELOADED`） |
| `dirty == true` | **不重读**，只发一次 `mdview:file-changed` | 走前端**既有**询问弹窗；**绝不动编辑器缓冲区**（数据丢失点，`PROMPTED`） |
| 读失败（被删 / 授权撤销） | 提示 + 走 `RemoveRecent` | 最近文件条目移除并弹 Toast（`UNAVAILABLE`） |

要点：

- **不做轮询、不起后台线程、不注册定时器**：策略只在 `onResume` 被调用一次（`MainActivity.onResume`），
  无状态、可重入，重复前后台切换不会叠加（前端弹窗是单个布尔 ref）。
- **`dirty == true` 绝不自动覆盖**：这是本 todo 存在的理由——外部变更 + 本地未保存时只弹窗询问，
  由用户决定；Kotlin 侧不读文件，前端也不会在脏时静默 `loadPath`。
- **自写窗口**：`SaveBindings` 在写入前调用 `SelfWriteWindow.mark()`（镜像 Go 在 `SaveFile` 顶部打标），
  让"保存后立刻回前台"不被误判为外部改动；`SelfWriteWindowTest` 锁定 500ms 边界。
- **无新事件、无新桥函数**：只复用冻结的 `mdview:file-changed`（`BridgeEvents.FILE_CHANGED`）。
- 前端无需改动：`bridge-events.ts` 已注册该事件、`App.vue` 的处理器已按 dirty 分流。

## 限制与设备项

- `[device]`：连续两次从文件管理器打开不同 `.md`，必须路由到**同一实例**
  （`launchMode="singleTask"` + `onNewIntent`）。无模拟器的 CI 无法执行，由 **todos 7/18** 在真机验证。
- `[device]`（todo 8）：预览里点击外部链接会打开系统浏览器；打开文件 / 外部修改时页面能收到上表事件。
  无模拟器的 CI 只能验证到"处理器已注册 + 名称逐字一致 + 纯逻辑单测"，**不得**用"代码看起来对"冒充真机通过。
- `[device]`（todo 19）：外部改动当前文档后切回前台——**无本地改动时内容刷新**；**有未保存改动时弹窗询问而非覆盖**。
  无模拟器的 CI 只能用 JVM 单测覆盖三分支，端到端需真机（**DEVICE-DEFERRED**）。Android **无实时外部变更检测**（见上节）。
- `[device]`（todo 11）：保存过程中强杀 → 重启出现原生三选一恢复弹窗，且默认项与长度判定一致。
  无模拟器无法执行（**DEVICE-DEFERRED**）。
- `[device]`（todo 12）：最近文件下拉显示正确文件名（而非 `content://` 原文）且可重开；把已记录的文档在外部删除后再重开 →
  该条目被自动移除且不崩溃。JVM 单测只覆盖模型/释放/命名，端到端 UI 需真机（**DEVICE-DEFERRED**）。
- `[device]`（todo 13）：本地存储含 `images/` 子目录的文档经 (b) 正常显示；从 `Download/` 打开给出提示而非白图；
  云盘来源文档给出"不支持"占位而非空白。无模拟器无法执行（**DEVICE-DEFERRED**）。
- `[device]`（todo 15）：真机上启动自动检查 / 手动"检查更新"能弹出新版本提示，点击后经系统浏览器打开下载页。本 todo 无独立 `[device]` 判据，最近的真机项是 todo 8 的"链接打开系统浏览器"；无模拟器无法执行（**DEVICE-DEFERRED**）。
- 桌面 `README.md:18` 的文件级承诺仅适用于 Windows；Android 的保存语义见上方"保存的可靠性"。

## 编码语义（todo 9，与桌面的两处差异）

编码检测 / 解码 / 回写与换行保真逐条移植 `mdview/app.go:459-546`，全部在纯 JVM 单测中运行
（`encoding/Encoding.kt`，只用 JDK 原生 charset，无 `android.icu.*`、无 Robolectric、无第三方编码库）。
与桌面相比有两处**刻意**差异，均由计划的字节保真契约驱动：

1. **字节保真回退显示为 `iso-8859-1`（桌面显示 `utf-8`）**。桌面把"既非合法 UTF-8 又非可信 CJK 检测"
   的内容一律标为 `utf-8` 并原样回写；单一 `utf-8` 标签无法同时表达"真 UTF-8（`café` → `C3 A9`）"
   与"字节保真回退（裸 `E9`）"，因此本移植用独立标签 `iso-8859-1` 承载回退，内容按"每字节 → 同码点"
   映射，绝不插入 U+FFFD。工具栏的编码徽标会显示 `iso-8859-1`。
2. **字节保真文档中键入非 Latin-1 字符（中文 / emoji）时提示"另存为 UTF-8"（桌面直接写成 UTF-8 字节、
   不提示）**。`iso-8859-1` 回写用 `CharsetEncoder` 配置 `onUnmappableCharacter(REPORT)`，不可映射即
   reject 并携带稳定令牌 `encoding-unmappable`，**绝不**用 Java 默认 REPLACE 静默写成 `?`；前端据该
   令牌弹出"另存为 UTF-8"提示，且只在用户确认后才把 `fileEnc` 切到 `utf-8` 并走另存为。

**探测顺序说明**：Go 原版在 GB18030/Big5 探测前先跑 `chardet` 统计检测；本移植以纯 Kotlin 的
统计判别器承担同一角色（`encoding/CommonHanStatisticDetector.kt`，注入 seam
`EncodingCodec.CharsetStatDetector`，零第三方依赖——`android.icu.text.CharsetDetector` 不在
Android 公开 SDK 内，android.jar 无任何字符集检测类）。方法是把字节**双向严格解码**后按
"简繁合并常用字表"命中数打分取高（这正是 chardet 频率信号的最小可移植内核；实测双向各
10 篇歧义文档——即对方字符集也能严格解码的那批——判对 10/10，错误方向命中数全为 0），
平局回退乱码区块信号，仍平局则"无意见"。这不是装饰性对齐：实测（桌面 JVM，纯严格探测）
10 个常见简体 GB18030 文档有 5 个能同时被 Big5 严格解码——GB18030 与 Big5 双字节空间大量
重叠，**任一固定顺序的纯严格探测都必然吃掉另一族**（Big5 先则 GBK 文件乱码，GB18030 先则
Big5 文件乱码），只有统计层能区分。统计层无意见（极短片段/双方得分相同）时回退 Big5 先行
的严格探测，此盲区在 `EncodingTest` 的 `ambiguousSimplified` 用例旁注明确声明。等价性只对
共享静态夹具语料断言（由 `android/tools/generate-encoding-fixtures.py` 生成），
不做"与 chardet 等价"声明。
`[device]` 追加一次 GB18030/Big5 真机往返：统计判别器与严格探测同为纯 JVM 代码，但设备端
charset 实现由系统 ICU 提供，JVM 通过**不蕴含**真机通过。

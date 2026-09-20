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

## Intent 排队规则

冷启动与热启动走**两条互斥的通道**，同一 URI 绝不重复派发：

1. **冷启动文件类 intent**（`ACTION_VIEW` / `ACTION_EDIT` 的 `data` URI）**不进队列**：
   在 `MainActivity.onCreate` 记入 `StartupFileGate`，由前端 `GetStartupFile` consume-once 取走
   （第二次为 `""`）。启动时没有文件（`dataString == null`）时仍回答 `""`，绝不触发空路径打开。
2. **热启动 intent**（`onNewIntent` 到达）**缓冲至 bridge-ready 后按序派发**：由
   `ReadyEventQueue` 在 ready 前入队、`markReady()` 时按到达顺序重放；ready 后即时派发。
3. **`ACTION_SEND` 的载荷按类型走事件**：`EXTRA_TEXT`（纯文本）经 `mdview:open-text` 交付，
   作为"未命名文档"载入；`EXTRA_STREAM`（URI）经 `mdview:open-path` 交付。
4. `ACTION_EDIT` 与 `ACTION_VIEW` **同处理**，不做区分。

> 待办：`mdview:open-text` 的 `App.vue` 事件处理器（以及 `mdview:open-path` 的守卫语义）
> 由 **todo 18** 完成；本 todo 只固定上述通道契约。`ACTION_SEND` 的 Intent 解析同样归 todo 18。

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
| `BridgeEvents.CONFIRM_EXIT` = `mdview:confirm-exit` | `AppEvents.confirmExit()` | `null` | `App.vue:1242` | 返回键 → **todo 18** |
| `BridgeEvents.OPEN_PATH` = `mdview:open-path` | `AppEvents.openPath(uri)` | JSON 字符串（文档 URI） | `App.vue:1245` | `onNewIntent` / `ACTION_SEND` → **todo 18** |
| `BridgeEvents.FILE_CHANGED` = `mdview:file-changed` | `AppEvents.fileChanged()` | `null` | `App.vue:1246` | 前台刷新 → **todo 19** |

`mdview:open-text`（分享纯文本 → 未命名文档）由 todo 18 实现，**不**属于本表；`BridgeEvents.REGISTERED`
刻意只含上表三个名字。payload 一律经 `BridgeCodec.quote` 做 JSON 编码，不做字符串拼接，
因此 URI 中的引号 / 换行 / `U+2028` / `U+2029` 无法越出 `evaluateJavascript` 的字符串边界。

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

## 限制与设备项

- `[device]`：连续两次从文件管理器打开不同 `.md`，必须路由到**同一实例**
  （`launchMode="singleTask"` + `onNewIntent`）。无模拟器的 CI 无法执行，由 **todos 7/18** 在真机验证。
- `[device]`（todo 8）：预览里点击外部链接会打开系统浏览器；打开文件 / 外部修改时页面能收到上表事件。
  无模拟器的 CI 只能验证到"处理器已注册 + 名称逐字一致 + 纯逻辑单测"，**不得**用"代码看起来对"冒充真机通过。
- 桌面 `README.md:18` 的"原子替换"承诺仅适用于 Windows；Android 的保存语义见 todo 11。

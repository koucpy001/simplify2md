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

## 保存的可靠性（todo 11）

Android 的 SAF 文档 URI **无法**做"临时文件 + rename 覆盖"，因此桌面 `README.md:18` 的文件级
承诺仅适用于 Windows；**Android 不提供原子替换**，只提供"写入失败回滚 + 启动对账恢复
（状态机 + 全量哈希）"。两者是不同的可靠性模型，不得混称。

保存流程（`storage/SaveStore.kt`）：

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

## 限制与设备项

- `[device]`：连续两次从文件管理器打开不同 `.md`，必须路由到**同一实例**
  （`launchMode="singleTask"` + `onNewIntent`）。无模拟器的 CI 无法执行，由 **todos 7/18** 在真机验证。
- `[device]`（todo 8）：预览里点击外部链接会打开系统浏览器；打开文件 / 外部修改时页面能收到上表事件。
  无模拟器的 CI 只能验证到"处理器已注册 + 名称逐字一致 + 纯逻辑单测"，**不得**用"代码看起来对"冒充真机通过。
- `[device]`（todo 11）：保存过程中强杀 → 重启出现原生三选一恢复弹窗，且默认项与长度判定一致。
  无模拟器无法执行（**DEVICE-DEFERRED**）。
- `[device]`（todo 12）：最近文件下拉显示正确文件名（而非 `content://` 原文）且可重开；把已记录的文档在外部删除后再重开 →
  该条目被自动移除且不崩溃。JVM 单测只覆盖模型/释放/命名，端到端 UI 需真机（**DEVICE-DEFERRED**）。
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

**探测顺序说明**：Go 原版在 GB18030/Big5 探测前先跑 `chardet` 统计检测；本移植不引入 chardet，
而 GB18030 是近超集、能严格解码绝大多数字节序列（包括 Big5 文本），因此**先探测 Big5、后探测 GB18030**
（Big5 更严格，严格解码成功是更强的信号），否则 Big5 文件会被误判为 GB18030 并在保存时被改写。
等价性只对共享静态夹具语料断言（`android/tools/generate-encoding-fixtures.py` 生成，
路径记录在 `.omo/evidence/task-9-android-apk-port.md`），不做"与 chardet 等价"声明。
`[device]` 追加一次 GB18030/Big5 真机往返：Android 的 charset 由 ICU 提供，JVM 通过**不蕴含**真机通过。

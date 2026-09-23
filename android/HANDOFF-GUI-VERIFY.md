# Android GUI 移动化 — Windows 端验证交接

> 手机端 GUI 已按移动优先重建：工具栏单行 + `⋯` 溢出菜单、手机默认预览、大纲改覆盖式抽屉、
> 状态行（文件名/编码/字数/状态提示）与触屏可达性修复。代码**已在本地 `main` 提交，但未推送**
> （`origin/main` 之后的提交清单见 §0；数量以 `git log --oneline origin/main..HEAD` 的真实输出为准，本交接文档自身也在其中）。
> **win 端要做的事**：拉取 → 构建带新前端产物的 APK → 真机跑 F3 端到端 → 顺手复测桌面 WebView2。
> 本文只列 **Linux 侧做不到** 的部分；Linux 侧已完成的实测见 `.omo/evidence/android-gui-mobile.md`。

## 为什么这些必须由 win 端做

- **本机 Gradle 不可用**：宿主只有 3.6 GB RAM（已冻结过一次）。`assembleRelease` 在这台机器上
  必须带 `-x stageFrontendAssets`（该任务会执行 `npm ci`，内存约束禁止），因此即使构建成功，
  **APK 里也不含新前端**——本机 Gradle 跑不出有意义的产物；完整链路只能由 Windows 本地或
  `release.yml` 的 Android job 执行。
- **没有真机 / 模拟器**：SAF 选择器、持久化授权、输入法遮挡、返回手势、安装与升级、图片树授权，
  这些只能在真机上验证（计划里统一标注 `[device]`）。
- **没有 Windows / WebView2**：桌面零回归只能在 Windows 上终验；Linux 侧只能用 Chromium 做
  改前/改后的 A/B（数值见 §4，WebView2 与浏览器的差异是 Linux 侧唯一无法排除的变量）。

---

## 0. 前置条件（推送 / 拉取）

本地 `main` 比 `origin/main` 领先 **7 个提交**（截至 `de0cdc9`，含该提交本身）。**本交接文档自身的修订提交会再 +1，因此数量以 `git log --oneline origin/main..HEAD` 的真实输出为准**；下表列出截至 `de0cdc9` 的全部条目（仓库约定：推送需要用户显式授权，编排者未推）。

| # | commit | 主题 |
|---|---|---|
| 1 | `34bb9b3` | docs: document the Android build, install and limitations |
| 2 | `5880132` | fix(frontend): collapse the mobile toolbar correctly and keep status feedback visible |
| 3 | `ced3c60` | feat(frontend): add the mobile-UI policy module and fit the phone toolbar on one row |
| 4 | `bbee40b` | fix(frontend): restore the desktop Save-As button and make the touch copy button visible |
| 5 | `2b4b8ff` | fix(frontend): make the phone status line and update-tip dismiss target usable |
| 6 | `2d89b5c` | chore(frontend): drop the unused toolbar-grouping policy and its tests |
| 7 | `de0cdc9` | docs(android): hand the device/Gradle/Windows verification over to the Windows side |

```bash
# Windows 端（PowerShell 或 Git Bash）
cd <repo>
git pull --ff-only origin main              # 若远端尚无这些提交，则由本机 push 后在其他机器拉取
git log --oneline origin/main..HEAD         # 本机领先的提交；应看到上表全部行（含本交接文档自身）
```

**推送前隐私扫描已复跑为空**：在 `2d89b5c` 上执行（更早的 `2b4b8ff` 同样为空）。操作方可自行复跑：

```bash
# 计划指定的两条（均应无输出）
git grep -lE "BEGIN (RSA|PRIVATE) KEY" -- . ':!.github' ':!*.md' ':!mdview/frontend/package-lock.json'
git status --porcelain | grep -E '\.(jks|keystore|p12)$'
# 更强的全仓复扫（含 .md 与 .github，同样无输出）
git grep -lE "BEGIN [A-Z ]*PRIVATE KEY"
```

> 注意：计划里的第一条正则有已知缺陷——它匹配不到最常见的两种私钥 PEM 头：PKCS#1 的 RSA
> 头与 OpenSSH 头（编排者已实测复现：以这两种真实头做探针会 MISSED；它只能匹配 PKCS#8 的
> 未加密头——即 BEGIN 段之后紧接 PRIVATE KEY 关键字的那种形式）。
> **不要把第一条当作唯一门禁**；第三条（`BEGIN [A-Z ]*PRIVATE KEY`）才是有效模式，
> 当前全仓为空，**确认无实际泄露**。

---

## 1. 构建并安装带新 GUI 的 APK

APK 内嵌的是被打包的前端产物，**GUI 改动只有重新构建才能到达手机**。

**方式 A：Windows 本地构建（建议先本地试装）**

```powershell
$env:ANDROID_KEYSTORE_PATH     = "C:\path\to\simplify2md-release.jks"
$env:ANDROID_KEYSTORE_PASSWORD = "<store password>"
$env:ANDROID_KEY_ALIAS         = "simplify2md"
$env:ANDROID_KEY_PASSWORD      = "<key password>"

cd <repo>\android
.\gradlew :app:assembleRelease -PrequireSigning=true
```

Git Bash 等价写法：`cd <repo>/android && ./gradlew :app:assembleRelease -PrequireSigning=true`。
细节与失败路径（缺材料时 `-PrequireSigning=true` 必须 FAIL）见 `android/SIGNING.md`。

**方式 B：打 tag 交给 `release.yml`**（windows + android 并行构建，唯一 publish 串行创建 Release）

```bash
git tag v0.3.0-rc2 && git push origin v0.3.0-rc2   # 预发布不占 Latest；tag 需用户授权
```

已知状态（勿重复核验，均已确认）：

- 4 个签名 Secret **已配置**：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、
  `ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`（名字可写，值只在 GitHub Secrets）。
- 已发布的 `v0.3.0-rc1` APK 证书**非 debug**：subject `CN=simplify2md`（自签，`O=simplify2md`），
  有效期至 **2054-02-07**。
- **体积基线**：`v0.3.0-rc1` 的 release APK = **3,697,325 B（3.53 MB）**。
  新前端会改变体积，请记录新产物的真实字节数（§6 第 6 项要用）。

安装（真机）：

```bash
adb install -r <新 APK>
```

---

## 2. F2 门禁全量（本机 Gradle，Linux 侧无法执行）

前端部分编排者已在 Linux 侧复跑（`vue-tsc`、`npm run build`、12 个 tsx 脚本全绿）；
**Kotlin 单测只做了静态审阅、没有执行**。下面的 Gradle 运行是 F2 验收缺失的另一半：

```bash
cd mdview/frontend && npx vue-tsc --noEmit          # 期望 exit 0
cd mdview/frontend && npm run build                 # 期望 exit 0，约 16s
cd mdview/frontend && npx tsx test-mobile-ui.ts     # 期望 "MOBILE UI OK: 11/11 cases hold."
# 其余 11 个 tsx 门禁：test-pipeline / test-bridge-parity / test-token-mapping /
# test-save-policy / test-paths / test-draft-binding / test-bridge-events /
# test-ime-helper / test-open-text / test-rel-link / test-web-env —— 逐个 exit 0

cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
# 期望：342 个 Kotlin @Test 方法、0 失败；lint 0 error（8 warning 为在册基线）
```

说明：`assembleRelease` 需要 §1 的签名材料；分支上缺材料时只告警并产出 unsigned，
**tag + `-PrequireSigning=true` 缺材料则直接 FAIL**（这是设计门禁，不是故障）。

请把原始输出贴回（或落盘到 `.omo/evidence/`），尤其 Kotlin 测试的
`tests / failures / errors / skipped` 与 lint 的 error/warning 计数。

---

## 3. F3 真机端到端 QA

### (a) 本次 GUI 新增复测项（8 条，最高优先级）

| # | 步骤 → 期望 |
|---|---|
| 1 | 全新安装或清数据后首次启动 → 默认进入 **预览**（不是分屏），正文可用宽度 ≥ 300dp |
| 2 | 工具栏为 **单行**（约 80dp），六项控件 `打开 / 保存 / 查找 / 编辑·预览 / ⋯` 全部在屏内可点 |
| 3 | 点 `⋯` → 菜单**恰为** `另存为 / 大纲 / 暗色 / 检查更新`，逐项生效（暗色即刻切换；检查更新有可见结果） |
| 4 | 打开 `大纲` → 正文宽度**不变**（覆盖式抽屉 + 遮罩，不挤压内容）；点条目跳转后抽屉自动关闭 |
| 5 | 手机竖屏 ↔ 横屏往返旋转 → 无残留遮罩/抽屉；横屏下大纲按桌面方式停靠（220dp） |
| 6 | 打开含宽表格的文档 → 表格**自身**横向滚动、不被裁剪；页面本身不出现横向溢出 |
| 7 | 代码块的 `复制` 按钮在触屏上**常显**且可点（成功则提示"已复制"，被拒则"复制失败"） |
| 8 | `查找` 栏停在屏幕底部、完整可点（"上一个 / 下一个 / 关闭"不被裁切） |

### (b) 全量 `[device]` 清单（计划 todos 2–21 汇总，按区域）

标 ⭐ 的 4 条是 F3 验收单独点名的关键项。

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
| 签名安装 | 安装 §1 产物成功；证书非 debug（`release.yml` 内建 `apksigner` 门禁在 tag 上会拒绝 debug 证书） |

**记录要求**：每条判据记录 **设备型号 / Android 版本 / APK 来源（本地构建或哪个 tag）/ 结果**，
截图或 logcat 片段落到 `.omo/evidence/`（计划 todos 2–21 各自的 `task-<N>-android-apk-port.md`，
或汇总一份新证据文件）。**不得**用"代码看起来对"冒充真机通过。

---

## 4. 桌面 WebView2 复测（零回归）

Linux 侧已用**改前 `App.vue` 的 A/B 构建**在 Chromium 上测过下列数值；WebView2 请逐项核对：

```bash
cd <repo>\mdview
wails build
```

| 检查 | 期望值 |
|---|---|
| 工具栏 | 单行、高约 41px；**10 个按钮**，顺序：打开 保存 另存为 查找 编辑 分屏 预览 大纲 暗色 检查更新 |
| `.statusline` | `display: contents`，不产生可见盒（文件名/编码/字数/状态与按钮同一行渲染） |
| 大纲 | 点击后停靠展开、宽 **220px**（非抽屉、无遮罩，正文相应变窄） |
| `.preview` 内边距 | `20px 28px` |
| 分屏 | 编辑/预览双栏与滚动同步正常（**手机端隐藏分屏，桌面必须保留**） |
| 编辑与快捷键 | Ctrl+O / Ctrl+S / Ctrl+Shift+S / Ctrl+F / F3 / Esc 行为与改前一致 |

---

## 5. 尚未解锁项（需要用户决定，不要擅自执行）

1. **CI 体积硬门禁（todo 21 的 gate 3）**：`android-ci.yml` 的体积步骤目前仍是
   "记录 + 警告"。要改成硬门禁，必须先有用户确认的预算。实测 release APK =
   **3,697,325 B（3.53 MB）**。计划回退规则有两种读法，都不理想：
   - 用 Wave-1 实测 **3,228,048 B** 作默认预算 → 当前构建**直接 FAIL**；
   - 乘 1.15 = **3,712,255 B** → 只剩约 **0.4%**（约 15 KB）余量，CI 会在下一次
     微小改动时变红。
   因此需要用户给出数字；在此之前判据 5 维持 **PASS-CONDITIONAL**（只记录 + 警告）。
2. **正式版 tag**：稳定版 `vX.Y.Z` 才会走 `--latest` 分支，也是 todo 21 发布验收的收口；
   需要用户的显式 tag 授权（`-rc` 标签保持 Pre-release，不得标记 Latest）。
3. **隐私扫描正则修正**：计划里的 `BEGIN (RSA|PRIVATE) KEY` 无法匹配最常见的两种私钥
   PEM 头（RSA 与 OPENSSH 两种头型，已复现）。建议改为 `BEGIN [A-Z ]*PRIVATE KEY`
   并同步计划条文；用该更强模式复扫当时为空，**无实际泄露**。

---

## 6. 回报清单

1. `origin/main` 之后全部提交的推送结果（截至 `de0cdc9` 为 7 个，含本交接文档；以 `git log --oneline origin/main..HEAD` 为准）；
2. 新 APK 的 `apksigner verify --print-certs` 输出（确认证书 DN 不含 `CN=Android Debug`）；
3. §2 门禁原始输出（含 Kotlin 测试 totals 与 lint error/warning 计数）；
4. §3 真机结果：逐条 + 设备型号 + Android 版本（§3(a) 的 8 条与 §3(b) 的 ⭐ 四条优先）；
5. §4 桌面 WebView2 观察结果（是否与表中数值逐项一致）；
6. 用户确认的 APK 体积预算数字（用于把 CI 体积步骤改为硬门禁）；
7. `gh release view <tag> --json assets` 输出（新 Release 的 3 个资产）。

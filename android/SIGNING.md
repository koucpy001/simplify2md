# Android 发布签名与发布流水线 — 操作交接

> 本文是 **todo 21（签名）与 todo 22（`release.yml` 三 job 重构）的操作规格**，
> 供在 Windows 端完成签名链路（生成 keystore、上传 Secret、本地验签）后接手。
>
> 代码侧已完成的部分见 `android/app/build.gradle.kts` 的 "Release signing + version
> injection" 段：签名判别与版本派生**已实现并验证**，本文只描述**剩余的人工步骤**。

---

## 0. 为什么签名必须在本地做

release APK 必须用非 debug 证书签名，否则装不上、也无法覆盖更新。而 **keystore 是发布私钥，
不应落在共享/云端服务器上** —— 因此生成、备份、上传都应在自己的机器上完成，
仓库里只保留 Secret 的**名字**，值只存在于 GitHub Secrets。

---

## 1. 生成 keystore

已有 release keystore 可跳过本节。

**Windows（cmd / PowerShell）** —— 若装了 Android Studio，可直接用它自带的 keytool：

```bat
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v ^
  -keystore simplify2md-release.jks ^
  -alias simplify2md ^
  -keyalg RSA -keysize 2048 -validity 10000 ^
  -storetype PKCS12
```

**Linux / macOS**

```bash
keytool -genkeypair -v \
  -keystore simplify2md-release.jks \
  -alias simplify2md \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12
```

要点：

- 别名 `simplify2md` 即后面 `ANDROID_KEY_ALIAS` 的值；
- **PKCS12 要求 store password 与 key password 相同**，就用同一个并存入密码管理器；
- `-validity 10000`（约 27 年），Google 要求至少 25 年；
- **keystore 必须离线备份** —— 丢失后无法再发布同包名更新，只能换包名。

---

## 2. base64 编码

**Linux / macOS**

```bash
base64 -w0 simplify2md-release.jks > keystore.b64
```

**Windows PowerShell**（系统没有 `base64` 命令）

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("simplify2md-release.jks")) | Set-Content -NoNewline keystore.b64
```

> 不要用 `certutil -encode`：它会插入换行与 `-----BEGIN/END-----` 头，需要额外清理。

---

## 3. 写入 4 个 GitHub Secret

| Secret 名称 | 值 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `keystore.b64` 的全部内容（**单行、无换行**） |
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | `simplify2md`（必须与 keytool 的 `-alias` 一致） |
| `ANDROID_KEY_PASSWORD` | key password（PKCS12 下与 store password 相同） |

**方式一：`gh` CLI**

```bash
# Linux / macOS / cmd
gh secret set ANDROID_KEYSTORE_BASE64 -R <owner>/<repo> < keystore.b64

# 其余三个交互式输入（不回显、不进 shell 历史）
gh secret set ANDROID_KEYSTORE_PASSWORD -R <owner>/<repo>
gh secret set ANDROID_KEY_ALIAS          -R <owner>/<repo>
gh secret set ANDROID_KEY_PASSWORD       -R <owner>/<repo>
```

> **PowerShell 注意**：`<` 是保留操作符，`gh secret set X < file` 会报错。改用管道：
>
> ```powershell
> Get-Content -Raw keystore.b64 | gh secret set ANDROID_KEYSTORE_BASE64 -R <owner>/<repo>
> ```

**方式二：GitHub 网页**
`https://github.com/<owner>/<repo>/settings/secrets/actions` → **New repository secret** → 逐个添加。

**校验**（GitHub 只允许列出名字，值永远读不出来）：

```bash
gh api repos/<owner>/<repo>/actions/secrets --jq '.secrets[].name'
```

---

## 4. 本地签名构建与验签（todo 21 的真 Secret 验收）

`android/app/build.gradle.kts` 直接读取环境变量，因此无需 CI 即可本地验收。

**Windows PowerShell**

```powershell
$env:ANDROID_KEYSTORE_PATH     = "C:\path\to\simplify2md-release.jks"
$env:ANDROID_KEYSTORE_PASSWORD = "<store password>"
$env:ANDROID_KEY_ALIAS         = "simplify2md"
$env:ANDROID_KEY_PASSWORD      = "<key password>"

cd <repo>\android
.\gradlew :app:assembleRelease -PrequireSigning=true

# 验签（把 36.0.0 换成本机已装的 build-tools 版本）
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
```

**通过标准**

- 产物是 `app-release.apk`（**不是** `app-release-unsigned.apk`）；
- 证书 DN **不含 `CN=Android Debug`**。

**反向验证（证明门禁有效）**

```powershell
Remove-Item Env:ANDROID_KEYSTORE_PATH
.\gradlew :app:assembleRelease -PrequireSigning=true   # 期望 BUILD FAILED
```

设计上：缺少签名材料时，分支/PR 构建只打 warning 并产出 unsigned；
一旦显式传 `-PrequireSigning=true`，Gradle 在**配置期**即抛错，**绝不产出 unsigned**。

---

## 5. 清理与备份

- 删除 `keystore.b64`（已上传完毕）；
- **离线备份 `simplify2md-release.jks`**；
- 确认仓库内无签名材料：`git status --porcelain | grep -E '\.(jks|keystore|p12|b64)$'` 应为空。
  建议在 `.gitignore` 中加入 `*.jks`、`*.keystore`、`*.p12`、`*.b64`。

---

## 6. todo 22：`release.yml` 重构为三 job

### 目标结构

```
build-windows (windows-latest) ─┐
                                 ├─→ publish (ubuntu-latest)  ← 唯一创建 Release
build-android (ubuntu-latest)  ─┘
```

**要消除的问题**：当前 `release.yml` 是单个 `windows-latest` job 直接创建 Release；
若让 Android 也去创建，就会出现"两个工作流各自争抢创建 Release"的竞态。
改成"两个构建并行 + 唯一 publish 串行"即可根除，**不需要**"N 次轮询重试"这类非确定性做法。

### 必须保留 / 不得改动

- `on: push: tags: ['v*']` 与 `permissions: contents: write` 原样保留；
- **Windows 产物的命名与内容不变**：`simplify2md.exe`、`simplify2md-amd64-installer.exe`，
  以及 `wails build -nsis -s -ldflags "-X main.appVersion=$GITHUB_REF_NAME"`；
- `release-notes.md` 的生成逻辑（含 `git describe` 取上一标签）不变，
  只是搬进 `build-windows` 并作为 artifact 上传（这样 `publish` 不需要完整 git 历史）；
- `gh release create` **只允许出现在 `publish` 中一次**；
- tag 构建**绝不产出 unsigned**。

### 关键步骤（完整 YAML 见下一节）

- `build-android`：先预检 `ANDROID_KEYSTORE_BASE64` 非空（为空即 fail），
  再解码到临时文件，然后
  `./gradlew :app:assembleRelease -PrequireSigning=true -PappVersionName="$GITHUB_REF_NAME"`。
  前端构建由 todo 3 的 Gradle 任务 `stageFrontendAssets` 唯一负责
  （与 `android-ci.yml` 同一设计，不重复 `npm ci`）。
- `publish`：`needs: [build-windows, build-android]`，用 `actions/download-artifact`
  显式取回 `release-notes.md` 与各产物；按 tag 计算 `LATEST_FLAG`：
  匹配 `^v[0-9]+\.[0-9]+\.[0-9]+$` 用 `--latest`，否则 `--prerelease`
  （**不得把预发布标记为 Latest**）。

### 完整 YAML

```yaml
name: Release

# 推送 v* 标签触发：两个构建 job 并行，唯一 publish job 串行创建 Release。
on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  build-windows:
    name: Windows portable + NSIS installer
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0          # 完整历史：release-notes 需要 git describe/log

      - uses: actions/setup-go@v5
        with:
          go-version: '1.25'
          cache-dependency-path: mdview/go.sum

      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: npm
          cache-dependency-path: mdview/frontend/package-lock.json

      - name: Frontend typecheck + build
        working-directory: mdview/frontend
        run: |
          npm ci
          npm run build

      - name: Install Wails CLI
        shell: pwsh
        run: |
          go install github.com/wailsapp/wails/v2/cmd/wails@v2.15.0
          Add-Content $env:GITHUB_PATH "$(go env GOPATH)\bin"

      - name: Install NSIS
        shell: pwsh
        run: |
          choco install nsis -y --no-progress
          Add-Content $env:GITHUB_PATH "C:\Program Files (x86)\NSIS"

      # -s 跳过 wails 自带 frontend 步骤（上一步已构建）。与原实现完全一致。
      - name: Build portable exe + NSIS installer
        shell: bash
        working-directory: mdview
        run: wails build -nsis -s -ldflags "-X main.appVersion=$GITHUB_REF_NAME"

      # release-notes 在此生成（本 job 有完整 git 历史），作为 artifact 传给 publish。
      - name: Build release notes
        shell: bash
        run: |
          {
            echo "## 下载"
            echo ""
            echo "| 文件 | 说明 |"
            echo "|---|---|"
            echo "| \`simplify2md-amd64-installer.exe\` | 安装版：开始菜单/桌面快捷方式、卸载器、注册 .md/.markdown 文件关联 |"
            echo "| \`simplify2md.exe\` | 便携版：单文件，拷贝即用 |"
            echo "| \`simplify2md-${GITHUB_REF_NAME}.apk\` | Android 版 |"
            echo ""
            echo "两个 Windows 版本均未签名，首次运行如遇 SmartScreen 提示，选择“更多信息 → 仍要运行”。WebView2 运行时 Windows 10/11 自带。"
            echo ""
            echo "## 更新内容"
            echo ""
            prev=$(git describe --tags --abbrev=0 "${GITHUB_REF_NAME}^" 2>/dev/null || true)
            if [ -n "$prev" ]; then
              git log --pretty=format:'- %s' "$prev..$GITHUB_REF_NAME"
            else
              git log --pretty=format:'- %s' "$GITHUB_REF_NAME"
            fi
          } > release-notes.md

      - name: Upload Windows artifacts
        uses: actions/upload-artifact@v4
        with:
          name: windows-dist
          path: |
            mdview/build/bin/simplify2md.exe
            mdview/build/bin/simplify2md-amd64-installer.exe
            release-notes.md

  build-android:
    name: Android signed release APK
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21 (Temurin)
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: actions/setup-node@v4
        with:
          node-version: 20

      # SDK 安装与 .github/workflows/android-ci.yml 保持一致：必须显式传 packages，
      # 否则 action 走默认行为尝试安装早已下架的 `tools` 包并失败
      # （"Failed to find package 'tools'"，v0.3.0-rc1 首跑即因此变红）。
      - uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools'
          accept-android-sdk-licenses: true
          log-accepted-android-sdk-licenses: false

      # setup-android 不会导出 SDK 根路径；与 android-ci.yml 相同地归一化两个变量名。
      - name: Export ANDROID_HOME / ANDROID_SDK_ROOT
        shell: bash
        run: |
          set -euo pipefail
          SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android/sdk}}"
          test -d "$SDK_ROOT"
          echo "ANDROID_SDK_ROOT=$SDK_ROOT" >> "$GITHUB_ENV"
          echo "ANDROID_HOME=$SDK_ROOT" >> "$GITHUB_ENV"
          echo "Android SDK root: $SDK_ROOT"

      - name: Install Android SDK packages
        run: |
          yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses > /dev/null
          sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --install \
            "platforms;android-36" "build-tools;36.0.0"

      # 缺 Secret 直接 fail —— 绝不在 tag 上产出 unsigned 发布产物。
      - name: Precheck signing secret
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          set -euo pipefail
          if [ -z "$ANDROID_KEYSTORE_BASE64" ]; then
            echo "::error::ANDROID_KEYSTORE_BASE64 is not configured — refusing to build an unsigned release"
            exit 1
          fi

      - name: Decode keystore
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          set -euo pipefail
          echo "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.jks"

      # 前端构建由 todo 3 的 Gradle 任务 stageFrontendAssets 唯一负责（与 android-ci.yml 同设计）。
      - name: Build signed release APK
        working-directory: android
        env:
          ANDROID_KEYSTORE_PATH: ${{ runner.temp }}/release.jks
          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          ANDROID_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          ANDROID_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: |
          set -euo pipefail
          chmod +x ./gradlew
          ./gradlew :app:assembleRelease -PrequireSigning=true -PappVersionName="$GITHUB_REF_NAME"

      # 确认产物已签名，且证书不是 debug 证书。
      - name: Verify the APK certificate is not the debug certificate
        working-directory: android
        run: |
          set -euo pipefail
          APK=$(ls app/build/outputs/apk/release/*.apk | grep -v unsigned | head -1)
          test -n "$APK"
          "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$APK" | tee /tmp/certs.txt
          if grep -q "CN=Android Debug" /tmp/certs.txt; then
            echo "::error::release APK is signed with the DEBUG certificate"
            exit 1
          fi

      - name: Name the APK
        working-directory: android
        run: |
          set -euo pipefail
          APK=$(ls app/build/outputs/apk/release/*.apk | grep -v unsigned | head -1)
          cp "$APK" "simplify2md-${GITHUB_REF_NAME}.apk"

      - name: Upload Android artifact
        uses: actions/upload-artifact@v4
        with:
          name: android-dist
          path: android/simplify2md-${{ github.ref_name }}.apk

  publish:
    name: Publish the release (sole creator)
    runs-on: ubuntu-latest
    needs: [build-windows, build-android]
    steps:
      - uses: actions/checkout@v4

      # 显式声明下载路径：两件 Windows 产物 + release-notes.md 平铺到 dist/
      - name: Download Windows artifacts
        uses: actions/download-artifact@v4
        with:
          name: windows-dist
          path: dist

      - name: Download Android artifact
        uses: actions/download-artifact@v4
        with:
          name: android-dist
          path: dist

      - name: Show downloaded files
        run: find dist -type f | sort

      # --latest 仅用于正式版 tag；预发布必须 --prerelease（不得标记为 Latest）
      - name: Compute LATEST_FLAG
        id: flag
        run: |
          set -euo pipefail
          if [[ "$GITHUB_REF_NAME" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "value=--latest" >> "$GITHUB_OUTPUT"
          else
            echo "value=--prerelease" >> "$GITHUB_OUTPUT"
          fi

      - name: Create the release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          gh release create "$GITHUB_REF_NAME" \
            --verify-tag \
            --title "simplify2md $GITHUB_REF_NAME" \
            --notes-file dist/release-notes.md \
            "${{ steps.flag.outputs.value }}" \
            dist/simplify2md.exe \
            dist/simplify2md-amd64-installer.exe \
            "dist/simplify2md-${GITHUB_REF_NAME}.apk"
```

### 验收

```bash
# 建议先用预发布 tag 试跑，避免误标 Latest
git tag v0.3.0-rc1 && git push origin v0.3.0-rc1

gh run list --workflow=release.yml --limit 1
gh release view v0.3.0-rc1 --json assets --jq '.assets[].name'
```

期望：3 个资产（两个 exe + `simplify2md-v0.3.0-rc1.apk`），
且该 Release 是 **Pre-release**（`-rc1` 不匹配 `^vX.Y.Z$`）。

```bash
gh run view <run-id> --log | grep release-notes.md
```

确认 `publish` 确实下载并使用了 `dist/release-notes.md`。

正式发版：`git tag v0.3.0 && git push origin v0.3.0` —— 此时才使用 `--latest`。

---

## 7. 版本派生规则（已在构建脚本中实现）

- `versionName` = tag 去掉 `v` 前缀，后缀保留（`v0.3.0-rc1` → `0.3.0-rc1`）；
- **先判 tag 性再解析**：仅当显式传 `-PappVersionName` **或** `GITHUB_REF_TYPE == 'tag'`
  才进入解析；分支/PR 构建在解析前回退占位值（`0.0.0` / `1`），
  因此分支名不会被当成版本解析、`android-ci` 不会因此变红；
- `versionCode = major*1000000 + minor*10000 + patch*100 + channel`，
  channel：正式版 `99`，预发布取后缀末尾整数（`-rc1` → 1、`-beta.2` → 2），
  须落在 1–98；无末尾整数（`-alpha`）、越界（`-rc0` / `-rc99`）、
  `major > 2099`、`minor/patch > 99` 一律显式失败；
- 由此同一版本的正式版 versionCode 严格大于其任何预发布
  （`v0.3.0` = 30099 > `v0.3.0-rc2` = 30002），可顺序覆盖安装。

---

## 8. 完成后需要回报的内容

1. `gh api repos/<owner>/<repo>/actions/secrets --jq '.secrets[].name'` 的输出（应 4 个名字）；
2. 本地 `apksigner verify --print-certs` 的输出（证书 DN，确认不含 `CN=Android Debug`）；
3. 反向验证结果（无签名材料 + `-PrequireSigning=true` → BUILD FAILED，且未产出 unsigned）；
4. 预发布 tag 试跑的 `gh release view ... --json assets` 输出；
5. APK 体积预算的确认数字（用于把 CI 的体积步骤由"记录 + 警告"改为硬门禁）。

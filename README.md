# simplify2md

> 极简 Markdown 查看编辑器 —— 浏览为主、编辑为辅。Windows 单文件，秒开。
> A minimal Markdown viewer & editor for Windows. One file, instant start.

<p align="center">
  <img src="docs/screenshots/split.png" width="900" alt="simplify2md 分屏模式：左侧源码编辑，右侧实时预览，右侧大纲导航">
</p>

## 特性

- **完整渲染管线**（移植自 [soloMD](https://github.com/whichonefen/soloMD)）：markdown-it + KaTeX 公式 + highlight.js 代码高亮 + 表格 / 脚注 / 任务列表 / 标题锚点 / GitHub 风格 Callout（`[!NOTE]` 等）
- **mineru / PDF 转换文档兼容**：自动修复 `\\命令`、`\_ {下标}`、`\&`、`\left{` 等 LaTeX 转义污染，多行公式行距按内容分级修正——打开即正常显示，无需手工清理
- **CodeMirror 6 编辑器**：Markdown 语法高亮、行号、软换行、撤销历史；输入法组词期跳过同步，中文输入不吃字
- **三种视图**：编辑 / 分屏（双向滚动同步）/ 预览
- **文内查找**：Ctrl+F 匹配计数，编辑区选中定位 + 预览黄色高亮，F3 循环跳转
- **大纲导航**：标题抽取，点击直达，随阅读位置高亮当前标题
- **编码与换行保真**：自动识别 UTF-8 / GB18030 / Big5，CRLF / LF 按原样写回，不损坏老文件；保存采用临时文件 + 原子替换，中途崩溃也不会写坏原文
- **文件关联与单实例**：双击 `.md` / `.markdown` 在已有窗口打开；应用外修改自动重载，有未保存改动时弹窗询问
- **暗色主题 · 字数统计 · 最近文件与启动恢复 · 未保存关闭守卫**
- **应用内更新检查**：启动自动检查 + 手动触发，区分"网络失败"与"已是最新"
- **mermaid 流程图**：` ```mermaid ` 围栏按需动态渲染，消毒后注入、主题跟随；渲染失败保留原文
- **代码块复制**：每个代码块悬浮"复制"按钮，一键复制源码
- **图片点击放大**：预览图片点击进入 lightbox 查看大图
- **目录折叠与阅读进度**：大纲可折叠；预览模式可用阅读进度条
- **自动保存草稿**：编辑内容节流自动保存，重启可恢复；主动放弃后清理草稿
- **图片懒加载**：预览图片滚动入视口才加载，配合 64 项 / 256MB LRU 缓存
- **清空最近记录**：最近文件下拉可一键清空

## 下载安装

从 [Releases](../../releases) 页面下载（Windows 10/11 x64）：

| 文件 | 说明 |
|---|---|
| `simplify2md-amd64-installer.exe` | 安装版：开始菜单 / 桌面快捷方式、卸载器、注册 `.md` / `.markdown` 文件关联（卸载时自动还原） |
| `simplify2md.exe` | 便携版：单文件，拷贝即用 |

两个版本均无签名，首次运行如遇 SmartScreen 提示，选择"更多信息 → 仍要运行"即可。
WebView2 运行时 Windows 10/11 自带；缺失时安装版会自动补装。

## 快捷键

| 按键 | 功能 |
|---|---|
| `Ctrl+O` / `Ctrl+S` | 打开 / 保存 |
| `Ctrl+Shift+S` | 另存为（无标题文档首次保存同此） |
| `Ctrl+F` | 查找（`Enter` 下一个、`Shift+Enter` 上一个） |
| `F3` / `Shift+F3` | 下一个 / 上一个匹配 |
| `Esc` | 关闭查找 / 取消弹窗 |

## 从源码构建

环境要求：Go 1.25+、Node.js 18+、[Wails CLI v2](https://wails.io/docs/gettingstarted/installation)；
打安装包另需 [NSIS](https://nsis.sourceforge.io)。

```bash
wails dev            # 开发模式（热重载）
wails build          # 便携版 → build/bin/simplify2md.exe
wails build -nsis    # NSIS 安装包 → build/bin/simplify2md-amd64-installer.exe
```

测试：

```bash
go test ./...          # Go 单测：编码检测 / 换行保真 / 图片路径解析 / 原子保存
cd frontend
npm install
npm run build          # 含 vue-tsc 类型检查
npx tsx test-pipeline.ts   # 渲染管线测试（内置 testdata/fixture.md，开箱可跑）
```

> 管线测试自带自包含夹具；若本地存在 `hybrid_auto/` 真实论文样本（不入库），
> 会额外对它做一轮冒烟渲染。CI（GitHub Actions, windows-latest）自动运行
> 上述全部检查。

## 技术栈

Wails v2 (Go + WebView2) · Vue 3 · CodeMirror 6 · markdown-it · KaTeX · highlight.js · fsnotify

## 目录结构

```
mdview/            应用主体（Go 后端 + Vue 前端）
  main.go          Wails 入口
  app.go           文件读写、编码/换行检测、外部监听、图片加载
  frontend/src/
    App.vue        界面与交互
    lib/cm-editor.ts    CodeMirror 编辑器装配
    lib/markdown.ts     渲染管线（含 mineru 转义修复、公式行距修正）
  build/           图标与安装器工程
hybrid_auto/       本地渲染测试样本（不入库）
```

## License

[MIT](LICENSE)

# mdview/frontend

`simplify2md` 的前端（Vue 3 + TypeScript + Vite）：Wails 桌面版与 Android WebView 外壳共用同一份界面与渲染/编辑逻辑。

## 构建

```bash
npm install
npm run build   # 含 vue-tsc 类型检查，产物在 dist/
```

## 测试

测试是无运行器的独立脚本（`test-*.ts`，用 `tsx` 逐个执行）：

```bash
npx tsx test-pipeline.ts
```

共 12 个脚本，完整清单与期望输出见 [android/README.md](../../android/README.md) 的「验证」节。

## 文档

- 平台绑定契约（`@bridge` 导出、事件、编码语义等）：[android/README.md](../../android/README.md)
- 应用级文档（Windows 版特性、构建与发布）：[根目录 README.md](../../README.md)

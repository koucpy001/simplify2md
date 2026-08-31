---
title: 管线测试夹具
imageRoot: ./images
---

# 管线测试夹具

一个自包含的渲染管线样本：不需要本地 hybrid_auto 论文即可运行
`test-pipeline.ts`，覆盖全部 leniency 修复路径。

## CJK 与强调

**限制：**硬链接是中文写作里最常见的强调形状，stock CommonMark 会
渲染成字面星号，`markdown-it-cjk-friendly` 修复它。

## 表格分隔行修复

表头 3 列、分隔行 4 列——markdown-it 会整表拒绝，需修复为 3 列：

| 名称 | 数值 | 单位 |
|---|---|---|---|
| 频率 | 24 | GHz |
| 带宽 | 500 | MHz |

## mineru 转义污染修复

下面的公式带 mineru 的双反斜杠命令与转义下标，渲染后不应残留：

$$
\\begin{array}{r l} s \_ {k} \& = \\left\[ j 2 \\pi \\int \right\]
\\end{array}\\tag{1}
$$

行内公式也要正常：$x^2 + y^2 = z^2$ 与 $\\alpha$。

## 代码块

已知语言要高亮，超长未知语言块只做转义（不自动探测）：

```python
def f(x):
    return x * 2
```

```
plain-unknown-language-sample
```

## 任务列表

- [x] 已完成项
- [ ] 未完成项

## Callout

> [!NOTE]
> 这是一个 GitHub 风格提示块。

## 脚注与缩写

一句话带脚注[^1]。

[^1]: 脚注正文。

## HTML 块

<table><tr><td>缩进的 HTML 表格</td></tr></table>

## 相对图片

![](images/fixture-figure.png)

## 子标题用于大纲

### 三级标题 A

### 三级标题 B

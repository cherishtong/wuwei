# WebSearch 联网搜索能力

声明：`"websearch": {}`

## API

```js
// 基本搜索（默认返回 5 条）
var result = capability.websearch.search("今天天气");
// result = {results: [
//   {title: "...", url: "https://...", snippet: "...", score: 0.9},
//   ...
// ]}

// 指定返回数量（最大 10）
var result = capability.websearch.search("最新新闻", 10);

// 解析结果
if (result.results && result.results.length > 0) {
  var first = result.results[0];
  capability.ui.set("title", "text", first.title);
  capability.ui.set("snippet", "text", first.snippet);
}
```

## 使用场景

- 实时信息查询（新闻、天气、股价）
- 资料检索
- 百科查询

## websearch vs ai.ask vs network.fetch

| 能力 | 用途 |
|------|------|
| `websearch` | 搜索互联网实时信息 |
| `ai.ask` | 灵活推理/总结/分析/代码生成（不需要具体 API 地址） |
| `network.fetch` | 调用已知的具体 API 端点 |

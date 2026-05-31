# A2UI 组件目录

ui.json 的 `components` 数组中每个元素是一个组件对象。

## 通用属性

- `id` — 唯一标识符（必填）
- `weight` — 在 Row/Column 中的 flex-grow 比例（可选）

## 布局组件

### Column — 垂直排列
```json
{"id": "root", "component": "Column", "children": ["a", "b"], "justify": "start", "align": "stretch"}
```
- `children`: id 字符串数组（必填）
- `justify`: "start" / "center" / "end" / "spaceBetween" / "spaceAround" / "spaceEvenly" / "stretch"
- `align`: "stretch" / "start" / "center" / "end"

### Row — 水平排列
属性同 Column，`justify` 控制水平方向，`align` 控制垂直方向。

## 内容组件

### Text — 文字显示（支持 Markdown）
```json
{"id": "title", "component": "Text", "text": "标题", "variant": "h1"}
```
- `text`: 纯字符串，支持 **粗体** `*斜体*` `行内代码` 列表 引用 代码块
- `variant`: "h1" / "h2" / "h3" / "h4" / "h5" / "body" / "caption"

### Icon — 图标
```json
{"id": "search-icon", "component": "Icon", "name": "search"}
```
name: accountCircle, add, arrowBack, arrowForward, attachFile, calendarToday, call, camera, check, close, delete, download, edit, event, error, fastForward, favorite, favoriteOff, folder, help, home, info, locationOn, lock, lockOpen, mail, menu, moreVert, moreHoriz, notificationsOff, notifications, pause, payment, person, phone, photo, play, print, refresh, rewind, search, send, settings, share, shoppingCart, skipNext, skipPrevious, star, starHalf, starOff, stop, upload, visibility, visibilityOff, volumeDown, volumeMute, volumeOff, warning

### Image — 图片
```json
{"id": "hero", "component": "Image", "url": "https://...", "fit": "cover", "variant": "mediumFeature"}
```
- `url`: 字符串或 `{"path": "/url"}`（动态）
- `fit`: "fill" / "contain" / "cover" / "none" / "scaleDown"
- `variant`: "icon" / "avatar" / "smallFeature" / "mediumFeature" / "largeFeature" / "header"

### Divider — 分割线
```json
{"id": "sep", "component": "Divider"}
```

## 交互组件

### Button — 按钮
```json
{"id": "submit-label", "component": "Text", "text": "提交"},
{"id": "submit-btn", "component": "Button", "child": "submit-label", "variant": "primary",
 "action": {"event": {"name": "submit-btn", "context": {"key": {"path": "/key"}}}}}
```
- `child`: Text/Icon 组件的 id（必填）
- `variant`: "default" / "primary" / "borderless"
- `action.event.name`: 必须等于 Button id
- `action.event.context`: 用 `{"path": "/..."}` 传递输入值给 handler
- label 组件不要放到任何 container 的 children 里

### TextField — 输入框
```json
{"id": "name-input", "component": "TextField", "label": "姓名", "value": {"path": "/name"}, "variant": "shortText"}
```
- `label`: 标签文字
- `value`: 字符串或 `{"path": "/..."}`（动态绑定）
- `variant`: "shortText" / "longText" / "number" / "obscured"

### CheckBox / Slider / ChoicePicker / DateTimeInput / Switch / RadioGroup / Select / Toggle / ToggleGroup / InputOTP
参见具体示例，模式类似。

## 复合组件

### Card — 卡片
```json
{"id": "card-inner", "component": "Column", "children": ["card-title", "card-text"]},
{"id": "card-title", "component": "Text", "text": "标题", "variant": "h3"},
{"id": "card-text", "component": "Text", "text": "内容"},
{"id": "my-card", "component": "Card", "child": "card-inner"}
```
- `child`: 单个子组件 id（必填）— 放多个用 Column 包装

### List — 列表
```json
{"id": "list", "component": "List", "children": ["item-1", "item-2"], "direction": "vertical"}
```

### Tabs — 标签页
```json
{"id": "tabs", "component": "Tabs", "tabs": [{"title": "标签1", "child": "content-1"}]}
```

### Modal / Sheet / Drawer / AlertDialog / Alert / Tooltip / HoverCard / Popover / DropdownMenu / ContextMenu / Command
参见示例。Modal/Sheet/Drawer 用 `trigger` + `content` 引用已有组件 id。

## 数据展示组件

### Accordion — 折叠面板
```json
{"id": "faq", "component": "Accordion", "items": [
  {"value": "item-1", "triggerText": "问题1", "contentId": "answer-1"},
  {"value": "item-2", "triggerText": "问题2", "contentId": "answer-2"}
]}
```
或者简化格式（兼容旧版）：
```json
{"id": "faq", "component": "Accordion", "items": [
  {"title": "问题1", "content": "answer-1"}
]}
```
- `items`: 每项 `{value, triggerText, contentId}` 或 `{title, content}`
- `content`/`contentId` 指向另一个组件的 id，该组件是折叠后展示的内容

### Table — 表格
```json
{"id": "table", "component": "Table",
 "columns": [{"title": "姓名", "key": "name"}, {"title": "年龄", "key": "age"}],
 "rows": [["张三", "28"], ["李四", "32"]]}
```

### Avatar / Badge / Progress / Skeleton / AspectRatio / ScrollArea
```json
{"id": "scroll", "component": "ScrollArea", "child": "long-content"}
```

### Carousel / Pagination / Breadcrumb / Collapsible
参见示例。

### Video / AudioPlayer
```json
{"id": "video", "component": "Video", "url": "https://example.com/video.mp4"}
{"id": "audio", "component": "AudioPlayer", "url": "https://example.com/audio.mp3", "description": "背景音乐"}
```

### Canvas — 绘图区域（仅 browser-js）
```json
{"id": "canvas", "component": "Canvas", "width": 800, "height": 600}
```

# StudyKeeper AI

学习监督助手的 **后端服务**（FastAPI）。当前处于 **8.6.1：流式输出（`/ai/chat/stream`）**——提供
`/health` 健康检查、`/ai/plan`（目标拆成带时间段的任务）、`/ai/chat`（对话 + 工具调用意图 + 用户画像 + 当前时间）、
`/ai/chat/stream`（SSE 流式对话：正文逐段推、工具调用攒齐后一次性给出）、
`/ai/parse-course`（自由格式课程文本 → 结构化课程列表）、
`/ai/extract-memory`（对话 → 值得长期记住的用户信息）。

## 目录结构

```
StudyKeeper-ai/
├── .env                    # 本地环境变量（含 API Key，已被 .gitignore 忽略）
├── .gitignore
├── requirements.txt        # 精确锁版本
├── README.md
├── app/                    # FastAPI 服务
│   ├── __init__.py
│   ├── main.py             # 应用入口：建 app、注册路由、指定 8000 端口
│   ├── config.py           # 读 .env，暴露 Settings / get_settings()
│   ├── utils/
│   │   ├── __init__.py
│   │   └── time_context.py # now_text()：当前时间文本（给模型看的「现在几点」）
│   ├── routers/
│   │   ├── __init__.py
│   │   ├── health.py       # GET  /health
│   │   └── ai.py           # POST /ai/plan、/ai/chat、/ai/chat/stream、/ai/parse-course、/ai/extract-memory
│   └── services/
│       ├── __init__.py
│       ├── llm_client.py   # DeepSeek 调用封装（chat_json / chat_with_tools / 流式）
│       ├── planner.py      # /ai/plan：提示词组装 + 返回内容解析校验
│       ├── tools.py        # 工具 schema：add_task / update_task / delete_task / complete_task
│       ├── chat.py         # /ai/chat 与 /ai/chat/stream：对话 + 工具调用意图（含流式拼接）
│       ├── course_parser.py # /ai/parse-course：课程文本解析与校验
│       └── memory.py       # /ai/extract-memory：用户画像记忆提炼与校验
└── main.py / clock.py / planner.py / scheduler.py / store.py   # 旧版命令行程序，保留不动
```

分层：`routers`（HTTP、参数校验、异常映射）→ `services`（业务）→ `config`（配置）；
`utils` 放与业务无关的通用纯函数（如当前时间文本），被 `services` 复用。
`services` 不依赖 FastAPI；`planner` 可脱离 Web 层直接调用。

## 环境要求

- Python 3.14.x（`.venv` 由 uv 基于 Python 3.14.7 创建）
- 依赖四项：`fastapi`、`uvicorn`、`python-dotenv`、`openai`

## 安装依赖

```powershell
uv venv --python 3.14
uv pip install --python .venv\Scripts\python.exe -r requirements.txt
```

## 配置

项目根目录的 `.env` 里放密钥，当前只读取一个变量：

| 变量名 | 用途 | 是否必需 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | DeepSeek 的 API Key，调用 `/ai/plan` 必需 | 调用 AI 接口时必需 |

缺失时服务照常启动（`/health` 正常），但 `/ai/plan` 会返回 500。`.env` 已被 `.gitignore` 忽略，请勿提交。

## 模型说明

`app/services/llm_client.py` 里配置了 `BASE_URL = "https://api.deepseek.com/v1"`、
`MODEL = "deepseek-flash"`。原 `deepseek-chat` 已不在 DeepSeek 现行模型列表中
（实测 `GET /models` 只返回 `deepseek-flash`、`deepseek-v4-pro`），换模型只改 `MODEL` 常量即可。

## 启动服务

在项目根目录执行：

```powershell
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
# 或者（等价，热重载已内置）
.venv\Scripts\python.exe -m app.main
```

- 接口文档：<http://127.0.0.1:8000/docs>
- OpenAPI：<http://127.0.0.1:8000/openapi.json>

## 验证

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
# status service
# ------ -------
# ok     study-agent-ai
```

返回 `{"status":"ok","service":"study-agent-ai"}` 即正常。

## 接口：POST /ai/plan

请求（`availableSlots` 也可以用 `["19:00-22:00"]` 这种字符串写法）：

```json
{
  "goal": "复习高数第三章 + 背 50 个单词",
  "planDate": "2026-09-18",
  "availableSlots": [
    {"startTime": "19:00", "endTime": "22:00"}
  ]
}
```

```powershell
$body = '{"goal":"复习高数第三章 + 背 50 个单词","planDate":"2026-09-18","availableSlots":[{"startTime":"19:00","endTime":"22:00"}]}'
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/ai/plan -ContentType 'application/json' -Body $body
```

响应：`{"tasks": [{"title": "...", "startTime": "19:00", "endTime": "19:45", "priority": "high"}, ...]}`

行为约定：

| 情况 | 结果 |
| --- | --- |
| 缺字段 / 时间格式非法 / 空 goal / 非法日期 / 多余字段 | `422`（FastAPI 校验，不消耗模型调用） |
| 缺 `DEEPSEEK_API_KEY`、网络失败、鉴权或限流、模型返回非法 JSON | `500`，detail 固定为 `"AI 服务调用失败"`（真实原因只写服务端 stderr 日志） |

服务端会再兜一层校验：任务时间必须完整落在 `availableSlots` 内、`endTime > startTime`、
`priority` 只能是 high/medium/low，越界条目直接丢弃；全部被丢弃则视为失败（500）。

system prompt 末尾会带上「当前时间」（如 `当前时间：2026-09-19 15:30（星期六）`，每次请求现取、
不缓存），让模型知道「今天」到底是哪天几点 —— `planDate` 只说明要排哪一天。

## 接口：POST /ai/chat

对话接口。模型想「增删改任务」时，不直接执行，而是通过工具调用（Function Calling）
把意图返回给调用方，**由 Java 侧执行**——Python 不执行工具、不连数据库、不调 Java、
也不做会话持久化（`history` 每次由调用方传进来）。

请求（`history`、`context` 及 `context` 里的五个列表都可以不传或传空列表）：

```json
{
  "message": "把背单词那条挪到 20:00 开始、20:45 结束",
  "history": [
    {"role": "user", "content": "今天任务好多"},
    {"role": "assistant", "content": "别急，一条条来。"}
  ],
  "context": {
    "todayTasks": [
      {"id": 12, "title": "背 50 个单词", "status": "pending",
       "startTime": "19:00", "endTime": "19:50"},
      {"id": 13, "title": "复习高数第三章", "status": "done",
       "startTime": "20:00", "endTime": "21:00"}
    ],
    "todayCourses": [
      {"courseName": "高等数学", "startTime": "08:00", "endTime": "09:40"}
    ],
    "todayRestTimes": [
      {"startTime": "12:00", "endTime": "12:30", "label": "午休"}
    ],
    "availableSlots": [
      {"startTime": "12:00", "endTime": "14:10"},
      {"startTime": "15:40", "endTime": "19:10"},
      {"startTime": "20:40", "endTime": "22:00"}
    ],
    "userMemories": [
      {"category": "event", "content": "用户下周要考雅思"},
      {"category": "goal", "content": "用户想 3 个月刷完力扣 hot100"}
    ]
  }
}
```

```powershell
$body = @'
{"message":"把背单词那条挪到 20:00 开始、20:45 结束","history":[],
 "context":{"todayTasks":[{"id":12,"title":"背 50 个单词","status":"pending","startTime":"19:00","endTime":"19:50"}],
            "todayCourses":[{"courseName":"高等数学","startTime":"08:00","endTime":"09:40"}],
            "todayRestTimes":[{"startTime":"12:00","endTime":"12:30","label":"午休"}],
            "availableSlots":[{"startTime":"12:00","endTime":"14:10"},{"startTime":"15:40","endTime":"19:10"}],
            "userMemories":[{"category":"event","content":"用户下周要考雅思"}]}}
'@
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/ai/chat -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

响应有两种形态（`reply` 与 `toolCalls` 至少有一个非空；两者都空时给兜底文案
「我没太理解，能再说一次吗？」）：

```json
// 1) 只是聊天
{"reply": "今天能坚持把高数复习完已经很棒了，累了就分两小段背单词。", "toolCalls": []}

// 2) 用户想改任务
{"reply": "", "toolCalls": [{"name": "update_task",
                             "arguments": {"taskId": 12, "startTime": "20:00", "endTime": "20:45"}}]}
```

四个可用工具（schema 见 `app/services/tools.py`）：

| 工具 | 必填参数 | 可选参数 |
| --- | --- | --- |
| `add_task` | `title` | `startTime`、`endTime`（HH:mm）、`priority`（high/medium/low） |
| `update_task` | `taskId` | `title`、`startTime`、`endTime`、`priority` |
| `delete_task` | `taskId` | — |
| `complete_task` | `keyword` | — |

`complete_task` 是给「我背完单词了」这类表达用的：模型只给一个关键词（如「单词」「数学」「错题」），
**怎么把它匹配到今日任务是 Java 侧的事**（Java 按 `title LIKE %keyword%` 把今天还没完成的任务批量标完成；
一条都没匹配到**不算失败**，Java 会把回复换成「没找到与'xx'相关的未完成任务」）。
Python 侧不执行工具、不做匹配、不校验关键词；用户没说清是哪一项时，模型应先反问而不是编造关键词。

「对话式排任务」：用户问「我今天想复习雅思，怎么做」这类规划问题时，模型**不会立刻建任务**，
而是先按 `context.availableSlots` 给出 2-5 条带具体时间段的建议，最后问一句
「要我把这几项加到今天的任务里吗？」。之后：

| 用户回复 | 模型行为 |
| --- | --- |
| 「好的」「可以」「ok」「加吧」「嗯」等同意词 | 每条建议返回一个 `add_task`（几项建议就有几次调用；仍然是「意图」，由 Java 侧执行） |
| 「我要 16:30-17:00 练听力」这类修改 | 保留其他建议，重新给一版**完整**建议列表（含改过的那条），再问一次「要加吗」，**不调工具** |
| 「算了」「不用」 | 不调工具，正常回复 |

这组规则写在 `app/services/chat.py` 的 `PLANNING_RULES`（拼在 `SYSTEM_PROMPT` 末尾）。
Python 侧不做配合逻辑：不解析建议、不复核建议是否落在空档内、也不缓存上一版建议 ——
上一版建议靠调用方通过 `history` 带回来，所以调用方要把「建议 + 用户回复」都带进 history。

`context.availableSlots` 注入 system prompt 就是一行（`startTime-endTime`、顿号分隔、最多列 50 条；
对象数组和 `["12:00-14:10", "15:40~19:10"]` 字符串写法都收）：

```
今天可用空档：12:00-14:10、15:40-19:10、20:40-22:00
```

空数组 / 不传时写成 `今天可用空档：（今天没有可用空档）`，模型据此回复「今天好像没有空档时间了」，
不会编造时段。空档怎么算（课程 + 已有任务 + 休息时段）是 Java 侧的事，Python 只负责展示。

画像注入（7.4 第 3 步）：`context.userMemories` 是 Java 侧从 `user_memory` 现查出来的用户画像记忆
（`category` + `content`，每类最多 3 条）。**非空**时 Python 把它追加到 system prompt 末尾：

```
你对该用户的了解：
- [重要事件] 用户下周要考雅思
- [长期目标] 用户想 3 个月刷完力扣 hot100

在回复时可以自然参考这些信息，但不要直接罗列，如果与当前对话无关不要硬扯。
```

分类中文映射：`habit`=学习习惯、`emotion`=情绪状态、`event`=重要事件、`preference`=偏好、
`goal`=长期目标；表里没有的类别（未知值）直接用英文原值。**空数组 / 不传时就不加这一段**
（只剩角色设定、今天的时间安排和当前时间）—— 画像只是「让模型认识用户」，不改任何工具调用行为。

system prompt 末尾还会带上当前时间（每次请求现取，不缓存）：

```
当前时间：2026-09-19 15:30（星期六）
```

模型自己没有时间概念，不告诉它就容易把「今天 / 明天 / 还有几天」算错。

行为约定：

| 情况 | 结果 |
| --- | --- |
| 空 message / 多传字段 / `history` 非数组 / `todayTasks`、`todayCourses`、`todayRestTimes`、`userMemories` 里的条目缺字段 / `availableSlots` 或 `userMemories` 超过 50 条 / `userMemories` 字段超长（category > 50、content > 500） | `422`（不消耗模型调用） |
| 缺 key、网络失败、鉴权或限流、工具参数不是合法 JSON 对象 | `500`，detail 固定为 `"AI 服务调用失败"` |

注意：一次请求最多返回一次 `toolCalls`，**不做多轮自动工具调用**；若模型返回了
工具名以外的名字，服务端原样透传（schema 已限定，正常不会发生）。

## 接口：POST /ai/parse-course

把一段自由格式的课程文本（多行，每行一条课）解析成结构化课程列表。解析不了的原始行
放进 `failed`，由前端提示用户手工修正——**不会强行编造**。Python 只解析文本：不查库、
不调 Java、不做课程去重（去重交给前端）。

请求：

```json
{"rawText": "每周一三五 8:00-9:40 高等数学\n每周二 14:00-15:40 大学英语\n周四早上 7点到8点40 大学物理\n周六 晚上 英语角"}
```

```powershell
$body = '{"rawText":"每周一三五 8:00-9:40 高等数学\n周六 晚上 英语角"}'
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/ai/parse-course -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

响应：

```json
{
  "courses": [
    {"courseName": "高等数学", "daysOfWeek": [1, 3, 5], "startTime": "08:00", "endTime": "09:40"},
    {"courseName": "大学英语", "daysOfWeek": [2],      "startTime": "14:00", "endTime": "15:40"},
    {"courseName": "大学物理", "daysOfWeek": [4],      "startTime": "07:00", "endTime": "08:40"}
  ],
  "failed": ["周六 晚上 英语角"]
}
```

- `daysOfWeek`：`1`=周一 … `7`=周日，升序去重。
- 服务端会再校验一遍：`daysOfWeek` 每个值必须在 1~7、时间必须能归一成 `HH:mm`、
  `endTime` 必须晚于 `startTime`；不合格的条目从 `courses` 剔除并尽力把原文那一行放进
  `failed`（`failed` 会去重，模型自己报的失败行也会带上）。
- 因为模型可能把多行合并或拆行，**不做「行数一致性」校验**，只管每一条合法不合法。

行为约定：

| 情况 | 结果 |
| --- | --- |
| `rawText` 为空 / 全空白 / 缺字段 / 多传字段 / 超过 5000 字 | `422`（不消耗模型调用） |
| 缺 key、网络失败、鉴权或限流、模型返回非法 JSON、`courses` 缺失 | `500`，detail 固定为 `"AI 服务调用失败"` |
| 全部行都解析失败（`courses` 为空但 `failed` 非空） | `200`，`courses: []` + 失败行清单 |
| `courses` 与 `failed` 同时为空（模型什么都没解析出来） | `500` |

## 接口：POST /ai/extract-memory

从最近几轮对话里提炼「值得长期记住」的用户信息（用户画像记忆的**第 1 步**，只提炼）。

请求（`messages` 最多 20 条，10 条左右即可；`role` 用 `user` / `assistant`）：

```json
{
  "messages": [
    {"role": "user", "content": "下周我要考雅思，有点慌"},
    {"role": "assistant", "content": "那这周先把口语和写作过一遍。"},
    {"role": "user", "content": "别给我排超过一小时的任务，我坐不住"}
  ]
}
```

```powershell
$body = '{"messages":[{"role":"user","content":"下周我要考雅思"},{"role":"assistant","content":"好"}]}'
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/ai/extract-memory -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

响应：

```json
{
  "memories": [
    {"category": "event", "content": "用户下周要考雅思"},
    {"category": "emotion", "content": "用户对英语有畏难情绪"},
    {"category": "preference", "content": "用户不喜欢超过 1 小时的任务"}
  ]
}
```

- `category` 只有 5 类：`habit`（学习习惯）、`emotion`（情绪状态）、`event`（重要事件）、
  `preference`（偏好）、`goal`（长期目标）。
- 提示词要求：只提炼用户主动提到的、可能持续影响后续对话的信息；一次性的临时信息、
  AI 说的话、用户提问本身都不提炼；每条 `content` 不超过 50 字、用第三人称描述。
- 服务端会再校验一遍：`category` 不在 5 类里的一律丢弃；`content` 空白丢弃、超长截断
  （上限 500 字）；最多返回 5 条；模型认为没有值得记住的信息时 `memories` 为 `[]`。
- **只提炼、不落库**：不查数据库、不调 Java、不做异步任务、不做缓存，也不做记忆合并 /
  遗忘、向量检索 / 语义相似度——存储与后续处理都在 Java 侧。

行为约定：

| 情况 | 结果 |
| --- | --- |
| `messages` 为空 / 全是空白内容 / 缺 `role` / 超过 20 条 / 多传字段 | `422`（不消耗模型调用） |
| 缺 key、网络失败、鉴权或限流、模型返回非法 JSON、`memories` 缺失 | `500`，detail 固定为 `"AI 服务调用失败"` |
| 模型认为没有值得记住的信息 | `200`，`memories: []` |

## 接口：POST /ai/chat/stream

流式版对话：正文一段段推给客户端（边生成边显示），工具调用意图攒齐后一次性给出。
**只做一次问答**：不做断线重连、不发心跳、不落库、不做多轮工具调用。

请求体与 `/ai/chat` **完全相同**（`message` + `history` + `context`）：`history` 同样会带给模型 ——
流式与非流式共用同一份 messages 组装逻辑（`chat.build_messages`），所以「用户同意上一轮建议」
这类依赖历史的回答在流式下也成立。前端（`ChatView.vue`）走的就是这个接口：

```json
{
  "message": "好的",
  "history": [
    {"role": "user", "content": "我今天想复习雅思，你觉得我该怎么做？"},
    {"role": "assistant", "content": "…1. 13:10-14:10 雅思听力…要我把这几项加到今天的任务里吗？"}
  ],
  "context": {
    "todayTasks": [
      {"id": 12, "title": "背 50 个单词", "status": "pending",
       "startTime": "19:00", "endTime": "19:50"}
    ],
    "todayCourses": [],
    "todayRestTimes": [],
    "availableSlots": [{"startTime": "13:10", "endTime": "14:10"}],
    "userMemories": []
  }
}
```

> 历史是「让模型记得自己上一轮说了什么」的唯一来源：**必须带**，否则模型看不到自己给过的建议，
> 「好的」会被当成没头没尾的一句话（Java 侧 `AiService.streamChat` 会从 `chat_message` 查最近 20 条填进来）。

```json
{
  "message": "把背单词那条挪到 20:00 开始、20:45 结束",
  "context": {
    "todayTasks": [
      {"id": 12, "title": "背 50 个单词", "status": "pending",
       "startTime": "19:00", "endTime": "19:50"}
    ],
    "todayCourses": [],
    "todayRestTimes": [],
    "userMemories": []
  }
}
```

```powershell
curl.exe -N -X POST http://127.0.0.1:8000/ai/chat/stream -H "Content-Type: application/json" -d "{\"message\":\"今天该先做什么\"}"
```

响应是 SSE（`Content-Type: text/event-stream`），每条以 `data: ` 开头、以空行结尾，
JSON 用 `ensure_ascii=False`（中文原样发）：

```
data: {"type": "delta", "content": "好，"}

data: {"type": "delta", "content": "我帮你把背单词挪到 20:00。"}

data: {"type": "tool_calls", "toolCalls": [{"name": "update_task", "arguments": {"taskId": 12, "startTime": "20:00", "endTime": "20:45"}}]}

data: {"type": "end", "reply": "好，我帮你把背单词挪到 20:00。"}

```

| 事件 | 说明 |
| --- | --- |
| `delta` | 模型刚吐出来的一小段正文；客户端直接追加显示即可（原样，不 trim） |
| `tool_calls` | 工具调用意图，结构与非流式 `/ai/chat` 的 `toolCalls` 完全一样（`name` + `arguments`）；没有就不发这条。流式下模型把一次调用拆成很多片（`index` 分组、`arguments` 逐片追加），Python 会按 `index` 拼完整再发 |
| `end` | 本轮结束，`reply` 是完整回复（已 trim）；客户端应以它为准落库 / 展示 |
| `error` | 出错（缺 key、网络、超时、工具参数不是合法 JSON 等）：`{"type":"error","message":"AI 服务调用失败"}`。流已经开始写了，HTTP 状态码改不了（仍是 200），真实原因只写服务端日志 |

行为约定：

| 情况 | 结果 |
| --- | --- |
| `message` 为空 / 全空白 / 多传字段 / `context` 里的条目缺字段 | `422`（不消耗模型调用） |
| 缺 key、网络失败、鉴权或限流、流中途断开、工具参数不是合法 JSON | `200` + `{"type": "error"}` 事件（前面已发出的 `delta` 不回撤） |
| 模型整轮什么都没说、也没想调工具 | 先发一条兜底文案的 `delta`（「我没太理解，能再说一次吗？」）再 `end`，与非流式 `/ai/chat` 一致 |

## 下一步（7.4 后续步骤）

用户画像记忆的后续步骤（记忆落库、注入对话、合并与遗忘）都在 Java 侧；Python 侧继续在
`services` 下扩展新的 AI 能力，复用 `llm_client.chat_json()` / `chat_with_tools()` 与
`Settings`，不要把大模型调用散落在路由里。

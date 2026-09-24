# 学习监督 Agent 接口文档 v1.0

## 0. 通用约定

### 0.1 基础信息

| 项 | 值 |
| --- | --- |
| 基础路径 | `/api` |
| 请求格式 | `application/json` |
| 响应格式 | `application/json` |
| 字符编码 | UTF-8 |
| 认证方式 | JWT（放在 Header） |

### 0.2 认证 Header

```
Authorization: Bearer <token>
```

除登录、注册外，所有接口都要带。

### 0.3 统一响应格式

```
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| code | int | 0 成功，非 0 失败 |
| message | string | 提示信息 |
| data | object / array / null | 业务数据 |

### 0.4 错误码

| code | 说明 |
| --- | --- |
| 0 | 成功 |
| 400 | 参数错误 |
| 401 | 未登录或 token 失效 |
| 404 | 资源不存在（不存在与不属于当前用户统一用 404） |
| 500 | 服务器错误 |
| 1001 | 邮箱已被注册（注册）/ 用户名已被占用（改资料） |
| 1002 | 账号或密码错误 |
| 3001 | AI 服务调用失败 |

### 0.5 时间格式

| 类型 | 格式 | 示例 |
| --- | --- | --- |
| 日期 | `YYYY-MM-DD` | `2026-09-17` |
| 时间 | `HH:mm` | `09:30` |
| 日期时间 | `YYYY-MM-DD HH:mm:ss` | `2026-09-17 09:30:00` |

### 0.6 枚举值

**task.status**

| 值 | 含义 |
| --- | --- |
| `pending` | 待完成 |
| `done` | 已完成 |
| `missed` | 未完成 |

**task.source**

| 值 | 含义 |
| --- | --- |
| `ai` | AI 拆解生成 |
| `template` | 长期任务实例化 |
| `manual` | 用户手动添加 |

**task.priority / task_template.priority**

| 值 | 含义 |
| --- | --- |
| `high` | 高 |
| `medium` | 中 |
| `low` | 低 |

**completion_record.action**

| 值 | 含义 |
| --- | --- |
| `done` | 完成 |
| `undone` | 取消完成 |
| `missed` | 到点未完成 |

**chat_message.role**

| 值 | 含义 |
| --- | --- |
| `user` | 用户 |
| `assistant` | AI |
| `system` | 系统 |

**user_memory.category**

| 值 | 含义 |
| --- | --- |
| `habit` | 学习习惯 |
| `emotion` | 情绪状态 |
| `event` | 重要事件 |
| `preference` | 偏好 |
| `goal` | 长期目标 |

---

## 1. 认证模块

> 注册与找回密码都是「邮箱 + 验证码」两步走：先调发码接口，再去邮箱拿 6 位验证码。
> 验证码 **5 分钟有效**、同一邮箱 **60 秒内不能重复发**、校验通过即作废（**一次性**），
> 只存在服务端内存里（后端重启后已发出的码作废，重新发一次即可）。
> 登录既支持邮箱也支持用户名 —— 改造前的老账号（只有用户名、没有邮箱）不受影响。

### 1.1 注册

```
POST /api/auth/register
```

**说明：** 需要先调 `POST /api/auth/send-email-code`（见 1.4）拿到验证码。

**请求体：**

```
{
  "email": "xiaoming@qq.com",
  "code": "123456",
  "password": "Abcdefg1",
  "nickname": "小明"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| email | string | 是 | 同时是登录账号；服务端 trim + 转小写后入库；最长 100 字符；**全局唯一** |
| code | string | 是 | 邮箱里收到的 6 位验证码，一次性 |
| password | string | 是 | 8-20 位，必须同时包含小写字母、大写字母、数字 |
| nickname | string | 否 | 默认取邮箱 `@` 前面那一段（最长 50 字符） |

> `username` **不再由前端传**：`user.username` 是 NOT NULL + UNIQUE，注册时自动取邮箱前缀
> （重名自动加数字后缀，如 `zhang` → `zhang2`），登录时用邮箱或这个用户名都可以。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "username": "xiaoming",
    "nickname": "小明"
  }
}
```

**错误：**

| code | 场景 |
| --- | --- |
| 1001 | 该邮箱已被注册 |
| 400 | 密码不满足强度要求 / 邮箱格式不正确 / 验证码错误或已过期 |

**说明：** 校验顺序是「密码强度 → 邮箱格式 → 验证码 → 邮箱是否已注册」—— 验证码放在查重之前，
是为了同时证明「这个邮箱确实归你」（否则接口能被拿来探测邮箱是否注册过）。
代价：邮箱已注册时会消耗掉一次验证码，重新发一次即可。

---

### 1.2 登录

```
POST /api/auth/login
```

**请求体：**

```
{
  "account": "student01",
  "password": "123456"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| account | string | 是 | 邮箱或用户名：含 `@` 按邮箱查（不区分大小写），否则按用户名查 |
| password | string | 是 | 明文密码，服务端用 BCrypt 校验（登录不校验强度，老弱密码仍可登录） |

> 老账号（只有用户名，如 `student01` / `乔治`）继续用用户名登录，**不需要任何迁移**；
> 邮箱注册的新账号用邮箱登录，或用自动生成的用户名登录。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "id": 1,
    "username": "student01",
    "nickname": "小明",
    "onboarded": false
  }
}
```

> `onboarded`（**新用户引导是否已完成**，见 §12.5 / §12.6）：库里 `user.onboarded` 是 `TINYINT`（1/0），
> 响应里是 `true/false`。**登录就带上它**，前端登录成功后直接决定要不要弹引导，不用再补一次请求；
> 新注册用户、以及加列之前就在的老用户都是 `false`。登录流程本身没变（token 的生成与校验一行没动），
> 只是响应里多了一个向后兼容的字段。

**错误：**

| code | 场景 |
| --- | --- |
| 1002 | 账号或密码错误（**不区分**账号不存在与密码错，避免暴露账号是否存在） |

---

### 1.3 退出登录

```
POST /api/auth/logout
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": null
}
```

---

### 1.4 发送注册验证码

```
POST /api/auth/send-email-code
```

**请求体：**

```
{ "email": "xiaoming@qq.com" }
```

**响应：**

```
{ "code": 0, "message": "success", "data": null }
```

**说明：**

- 邮件主题「【学习监督】验证码」，正文「你的验证码是：123456，5 分钟内有效」；
- **发信是异步的**（后端线程池 `email-send-*`，核心 2 / 最大 4 / 队列 100）：接口只做「校验 + 生成验证码 + 存内存」就返回 200，
  **不等 SMTP** —— SMTP 慢的时候（10 秒级）不会再触发前端超时（见 `docs/decisions.md` §13）；
- 因为是异步，**响应成功 ≠ 邮件一定送到**：发信失败（SMTP 连不上 / 授权码不对）只写后端日志，用户侧看不到错误，
  没收到就等 60 秒限流过后重发一次（后端不做重试、不发邮件队列）；
- 响应里**不带验证码**（码只进邮箱）；这里**不校验该邮箱是否已注册**（注册时再判重并返回 1001）；
- 同一邮箱 60 秒内只能发一次（防刷）；验证码只存在服务端内存里，**后端重启后已发出的码作废**（重新发一次即可）；
- 发信依赖环境变量 `MAIL_USERNAME` / `MAIL_PASSWORD`（QQ 邮箱 + SMTP 授权码）：**没配时同步返回 400** ——
  这类配置错当场就能发现，不丢给异步线程（否则用户会拿到「成功」却永远收不到信）。

**错误：**

| code | 场景 |
| --- | --- |
| 400 | 邮箱格式不正确 / 验证码发送过于频繁（文案带剩余秒数）/ 未配置发件邮箱（文案：邮件发送失败，请稍后再试） |

---

### 1.5 找回密码 · 发送验证码

```
POST /api/auth/forgot-password/send-code
```

**请求体：**

```
{ "email": "xiaoming@qq.com" }
```

**响应：** 同 1.4（`data` 为 `null`）。

**说明：**

- 与 1.4 共用同一套发码逻辑（主题 / 正文 / 60 秒间隔 / 5 分钟有效期 / 只存内存）；
- **同样是异步发信**（同一线程池 `email-send-*`）：接口不等 SMTP，发信失败只写后端日志（同 1.4 的第 2、3 条）；
- 区别是**这里要求邮箱已注册**：没注册过直接返回 400「该邮箱未注册」——
  按「体验优先」明确告知（代价是该接口可被用来探测某邮箱是否注册过，个人项目接受）；
- `POST /api/auth/forgot-password` 是本接口的**别名**：第 1 步曾先上过一版「未注册也返回成功」的占位实现，
  现在两处统一为同一行为，前端新代码请用 `/api/auth/forgot-password/send-code`。

**错误：**

| code | 场景 |
| --- | --- |
| 400 | 该邮箱未注册 / 邮箱格式不正确 / 验证码发送过于频繁 / 未配置发件邮箱（文案：邮件发送失败，请稍后再试） |

---

### 1.6 找回密码 · 重置密码

```
POST /api/auth/forgot-password/reset
```

**请求体：**

```
{
  "email": "xiaoming@qq.com",
  "code": "123456",
  "newPassword": "NewPass1"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| email | string | 是 | 注册时用的邮箱 |
| code | string | 是 | 1.5 发出去的 6 位验证码，**一次性** |
| newPassword | string | 是 | 8-20 位，必须同时包含小写字母、大写字母、数字 |

**响应：**

```
{ "code": 0, "message": "success", "data": null }
```

**说明：**

- 校验顺序：邮箱格式 → 邮箱是否注册 → 新密码强度 → 验证码；
  **只有最后一步成功才消耗验证码**，前面几步失败时用户可以直接改输入重试，不用重发；
- 成功后 `user.password` 换成新的 BCrypt 哈希（只更新 `password` 与 `updated_at` 两列），旧密码立刻失效；
- **不强制下线**：JWT 无状态、不做黑名单，已签发的 token 到过期前仍然可用。
  如果要做「改密码即踢下线」，可给 token 加一个密码版本号 claim（或 `password_changed_at`），
  在 `JwtInterceptor` 里比对 —— 这是**另一件事**，本步没做。

**错误：**

| code | 场景 |
| --- | --- |
| 400 | 该邮箱未注册 / 新密码不满足强度要求 / 验证码错误或已过期 |

---

## 2. 任务模块

### 2.1 获取今日任务

```
GET /api/task/today
```

**说明：** 查询前会先做一次「懒加载实例化」——把今天该出现的长期任务（`active=1` 且 `repeatDays` 命中今天）补生成成今日任务，
所以第二天打开今日页也能看到长期任务；同模板同一天不会重复生成（幂等）。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "date": "2026-09-17",
    "total": 5,
    "completed": 2,
    "percent": 40,
    "tasks": [
      {
        "id": 1,
        "title": "背单词 50 个",
        "description": null,
        "startTime": "07:00",
        "endTime": "07:30",
        "duration": 30,
        "priority": "high",
        "status": "done",
        "source": "template",
        "templateId": 101,
        "completedAt": "2026-09-17 07:28:00"
      },
      {
        "id": 2,
        "title": "数学练习册 P20-22",
        "description": null,
        "startTime": "09:00",
        "endTime": "10:00",
        "duration": 60,
        "priority": "medium",
        "status": "pending",
        "source": "ai",
        "templateId": null,
        "completedAt": null
      }
    ]
  }
}
```

---

### 2.2 创建任务

```
POST /api/task
```

**请求体：**

```
{
  "title": "做数学卷子",
  "description": "第二章习题",
  "planDate": "2026-09-17",
  "startTime": "20:00",
  "endTime": "21:00",
  "priority": "medium"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| title | string | 是 | 任务标题 |
| description | string | 否 | 描述 |
| planDate | string | 是 | 属于哪一天 |
| startTime | string | 否 | 开始时间 |
| endTime | string | 否 | 结束时间 |
| priority | string | 否 | 默认 `medium` |

**说明：** `duration` 由 `startTime` 和 `endTime` 自动计算，不需要前端传。`source` 固定为 `manual`。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 10,
    "title": "做数学卷子",
    "planDate": "2026-09-17",
    "startTime": "20:00",
    "endTime": "21:00",
    "duration": 60,
    "status": "pending",
    "source": "manual"
  }
}
```

---

### 2.3 修改任务

```
PUT /api/task/{id}
```

**请求体：**

```
{
  "title": "做数学卷子（改）",
  "startTime": "20:30",
  "endTime": "21:30",
  "priority": "high"
}
```

**说明：** 只传要改的字段。`status` 不能通过这个接口改，用 2.4 / 2.5。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 10,
    "title": "做数学卷子（改）",
    "startTime": "20:30",
    "endTime": "21:30",
    "duration": 60
  }
}
```

**错误：**

| code | 场景 |
| --- | --- |
| 404 | 任务不存在或不属于当前用户 |

---

### 2.4 删除任务

```
DELETE /api/task/{id}
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": null
}
```

---

### 2.5 标记完成

```
POST /api/task/{id}/done
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 10,
    "status": "done",
    "completedAt": "2026-09-17 20:45:00"
  }
}
```

## **说明：** 同时写入一条 `completion_record`，`action = done`。

### 2.6 取消完成

```
POST /api/task/{id}/undo
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 10,
    "status": "pending",
    "completedAt": null
  }
}
```

## **说明：** 同时写入一条 `completion_record`，`action = undone`。

### 2.7 批量确认 AI 拆解的任务

```
POST /api/task/batch
```

**说明：** 用于「AI 帮我排」对话框，用户确认后一次性提交多条。
**请求体：**

```
{
  "planBatchId": "batch_20260917_001",
  "planDate": "2026-09-17",
  "tasks": [
    {
      "title": "复习数学 · 知识梳理",
      "startTime": "09:00",
      "endTime": "10:00",
      "priority": "high"
    },
    {
      "title": "复习数学 · 专项练习",
      "startTime": "14:00",
      "endTime": "15:00",
      "priority": "medium"
    }
  ]
}
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "planBatchId": "batch_20260917_001",
    "createdCount": 2,
    "ids": [11, 12]
  }
}
```

**说明：**

- `planBatchId` 由前端或 AI 服务生成，用于后续撤销/重排；
- `source` 固定为 `ai`。

---

## 3. 长期任务模块

### 3.1 获取长期任务列表

```
GET /api/template
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": 101,
      "title": "每日背单词",
      "duration": 30,
      "priority": "high",
      "repeatDays": [1, 3, 5],
      "difficulty": 1.00,
      "active": true
    },
    {
      "id": 102,
      "title": "数学压轴题训练",
      "duration": 45,
      "priority": "medium",
      "repeatDays": [2, 4],
      "difficulty": 1.00,
      "active": true
    }
  ]
}
```

---

### 3.2 新增长期任务

```
POST /api/template
```

**请求体：**

```
{
  "title": "每日背单词",
  "description": null,
  "duration": 30,
  "priority": "high",
  "repeatDays": [1, 3, 5]
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| title | string | 是 | 标题 |
| duration | int | 是 | 分钟 |
| priority | string | 否 | 默认 `medium` |
| repeatDays | int[] | 是 | 1‑7，至少一个 |

**说明：** 如果选“每天”，传 `[1,2,3,4,5,6,7]`。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 103,
    "title": "每日背单词",
    "duration": 30,
    "repeatDays": [1, 3, 5],
    "active": true
  }
}
```

---

### 3.3 修改长期任务

```
PUT /api/template/{id}
```

## **请求体：** 同 3.2，只传要改的字段。

### 3.4 删除长期任务

```
DELETE /api/template/{id}
```

---

### 3.5 启用 / 停用

```
PUT /api/template/{id}/toggle
```

**请求体：**

```
{
  "active": false
}
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 103,
    "active": false
  }
}
```

---

## 4. 课程表模块

### 4.1 获取课程列表

```
GET /api/course?dayOfWeek=1
```

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| dayOfWeek | int | 否 | 1‑7，不传返回全部 |

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": 1,
      "courseName": "高等数学",
      "dayOfWeek": 1,
      "startTime": "08:00",
      "endTime": "09:40",
      "location": "A101"
    },
    {
      "id": 2,
      "courseName": "大学英语",
      "dayOfWeek": 1,
      "startTime": "10:00",
      "endTime": "11:40",
      "location": "B203"
    }
  ]
}
```

---

### 4.2 新增课程

```
POST /api/course
```

**请求体：**

```
{
  "courseName": "高等数学",
  "dayOfWeek": 1,
  "startTime": "08:00",
  "endTime": "09:40",
  "location": "A101"
}
```

**说明：** 一次只能加一天。前端选了多个星期，就循环调用多次。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 3,
    "courseName": "高等数学",
    "dayOfWeek": 1,
    "startTime": "08:00",
    "endTime": "09:40"
  }
}
```

---

### 4.3 修改课程

```
PUT /api/course/{id}
```

## **请求体：** 同 4.2，只传要改的字段。

### 4.4 删除课程

```
DELETE /api/course/{id}
```

---

## 5. 休息时间模块

### 5.1 获取休息时间列表

```
GET /api/rest
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": 1,
      "dayOfWeek": null,
      "startTime": "10:00",
      "endTime": "10:20",
      "label": "课间休息"
    },
    {
      "id": 2,
      "dayOfWeek": 1,
      "startTime": "15:00",
      "endTime": "15:15",
      "label": "下午茶"
    }
  ]
}
```

## **说明：** `dayOfWeek = null` 表示每天生效。

### 5.2 新增休息时间

```
POST /api/rest
```

**请求体：**

```
{
  "dayOfWeek": null,
  "startTime": "10:00",
  "endTime": "10:20",
  "label": "课间休息"
}
```

---

### 5.3 修改休息时间

```
PUT /api/rest/{id}
```

---

### 5.4 删除休息时间

```
DELETE /api/rest/{id}
```

---

## 6. 统计模块

### 6.1 今日统计

```
GET /api/stats/today
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "date": "2026-09-17",
    "total": 5,
    "completed": 2,
    "percent": 40
  }
}
```

---

### 6.2 本周统计

```
GET /api/stats/week
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "weekStart": "2026-09-15",
    "weekEnd": "2026-09-21",
    "total": 20,
    "completed": 12,
    "percent": 60
  }
}
```

---

### 6.3 近 N 天趋势

```
GET /api/stats/trend?days=7
```

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| days | int | 否 | 7 | 1‑30 |

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": [
    { "date": "2026-09-11", "completed": 4 },
    { "date": "2026-09-12", "completed": 3 },
    { "date": "2026-09-13", "completed": 5 },
    { "date": "2026-09-14", "completed": 2 },
    { "date": "2026-09-15", "completed": 4 },
    { "date": "2026-09-16", "completed": 3 },
    { "date": "2026-09-17", "completed": 5 }
  ]
}
```

---

## 7. 聊天模块

### 7.1 获取历史消息

```
GET /api/chat/history?limit=50
```

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| limit | int | 否 | 50 | 最多 200；不是 1-200 的数字返回 400 |

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": 1,
      "role": "user",
      "content": "帮我安排一下今天的任务",
      "createdAt": "2026-09-17 08:30:00"
    },
    {
      "id": 2,
      "role": "assistant",
      "content": "好的，你今天想重点做什么？",
      "createdAt": "2026-09-17 08:30:05"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | long | 消息 id |
| role | string | `user` / `assistant`，见 §0.6 |
| content | string | 消息内容；AI 只调工具没说话时库里存的是 `（无回复）` |
| createdAt | string | `YYYY-MM-DD HH:mm:ss` |

**说明：**

- 数据来自 `chat_message` 表（由 §9.2 `POST /api/ai/chat` 落库），只返回当前 token 对应用户的消息；
- 按时间**正序**返回（最早的在前），最后一条是最新消息；
- 一条消息都没有时 `data` 是空数组 `[]`（不是 `null`）；
- 只读接口：不分页、不删除、不做关键词搜索。

**错误：**

| code | 场景 |
| --- | --- |
| 400 | limit 不是 1-200 的数字 |
| 401 | 未登录 / token 失效 |

---

### 7.2 发送消息

> 实际实现是 `POST /api/ai/chat`，见 §9.2；发消息这一轮的用户消息与 AI 回复会自动落库，
> 前端刷新后调 §7.1 即可拉回完整历史。

```
POST /api/chat/send
```

**请求体：**

```
{
  "content": "今天要复习数学，准备英语考试"
}
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "reply": "好的，我帮你安排了 3 条任务，已添加到今日任务。",
    "toolCalls": [
      {
        "tool": "add_task",
        "args": {
          "title": "复习数学 · 知识梳理",
          "startTime": "09:00",
          "endTime": "10:00"
        }
      },
      {
        "tool": "add_task",
        "args": {
          "title": "复习数学 · 专项练习",
          "startTime": "14:00",
          "endTime": "15:00"
        }
      },
      {
        "tool": "add_task",
        "args": {
          "title": "英语考试准备",
          "startTime": "19:00",
          "endTime": "20:00"
        }
      }
    ],
    "affectedTaskIds": [20, 21, 22]
  }
}
```

**说明：**

- 如果 AI 识别出用户想添加课程，会返回 `toolCalls` 包含 `add_course`；
- 前端根据 `toolCalls` 刷新对应页面；
- 后端负责执行工具调用，前端只负责展示。

---

## 8. 用户画像记忆模块

「我的画像」页用：让用户看到 AI 记住了什么、自己补一条、删掉不想要的。
记忆的**自动沉淀**由聊天链路负责（`POST /api/ai/chat` 成功后异步提炼，见 §9.5），
这里只做**人工查看 / 新增 / 删除**：不做编辑、不做批量删除、不做分类统计。

### 8.1 获取记忆列表

```
GET /api/memory
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": 3,
      "category": "event",
      "content": "用户下周要考雅思",
      "createdAt": "2026-09-17 08:30:00",
      "updatedAt": "2026-09-17 08:30:00"
    },
    {
      "id": 5,
      "category": "goal",
      "content": "用户想 3 个月刷完力扣 hot100",
      "createdAt": "2026-09-17 09:00:00",
      "updatedAt": "2026-09-17 09:00:00"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | long | 记忆 id（删除时用） |
| category | string | 见 §0.6 的 `user_memory.category` |
| content | string | 记忆内容，最长 500 字 |
| createdAt / updatedAt | string | `YYYY-MM-DD HH:mm:ss` |

**说明：**

- 只返回当前 token 对应用户的记忆；没有记忆时 `data` 是空数组 `[]`；
- 排序：**按分类分组**，组间顺序固定 `event` > `goal` > `emotion` > `habit` > `preference`（重要的在前），
  组内按 `updatedAt` 倒序；不在 5 类里的历史脏数据排在最后；
- 不分页：一个人的画像条数很少，全量返回。

---

### 8.2 手动新增一条记忆

```
POST /api/memory
```

**请求体：**

```
{
  "category": "goal",
  "content": "用户想 3 个月刷完力扣 hot100"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| category | string | 是 | 只能是 `habit` / `emotion` / `event` / `preference` / `goal`（大小写敏感），其他值返回 400「无效的分类」 |
| content | string | 是 | 去首尾空白后不能为空，最长 500 字 |

**响应：** `data` 是新增成功的那条记忆（结构同 §8.1，含服务端生成的 `id` 与时间）。

**错误：**

| code | 场景 |
| --- | --- |
| 400 | category 不是 5 类之一（`无效的分类`） |
| 400 | content 为空（`content 不能为空`）或超过 500 字（`content 长度不能超过 500 字`） |

**说明：** 不做重复校验 —— 用户手动加的即使与已有记忆一模一样也照存（去重只发生在自动提炼那条链路上）。

---

### 8.3 删除一条记忆

```
DELETE /api/memory/{id}
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": null
}
```

**错误：**

| code | 场景 |
| --- | --- |
| 404 | 记忆不存在，或不属于当前用户（两种情况统一 404，不区分） |

---

## 9. AI 服务接口（Java → Python）

这组接口**不直接暴露给前端**，是 Java 后端调用 Python AI 服务用的。

### 9.1 AI 拆解今日任务

```
POST /ai/plan
```

**请求体：**

```
{
  "userId": 1,
  "planDate": "2026-09-17",
  "goal": "复习数学，准备英语考试",
  "availableSlots": [
    { "start": "08:00", "end": "09:00" },
    { "start": "12:00", "end": "14:00" },
    { "start": "18:00", "end": "22:00" }
  ]
}
```

**说明：**

- `availableSlots` 由 Java 算好：一天总时段 − 课程表 − 休息时间 − 当天已有任务（含已完成）；
- 传 `availableSlots` 是为了让 AI 在约束内排任务。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "tasks": [
      {
        "title": "复习数学 · 知识梳理",
        "startTime": "09:00",
        "endTime": "10:00",
        "priority": "high"
      },
      {
        "title": "复习数学 · 专项练习",
        "startTime": "14:00",
        "endTime": "15:00",
        "priority": "medium"
      },
      {
        "title": "英语考试准备",
        "startTime": "19:00",
        "endTime": "20:00",
        "priority": "high"
      }
    ]
  }
}
```

**错误：**

| code | 场景 |
| --- | --- |
| 3001 | AI 服务调用失败 |
| 400 | AI 返回格式不合法 |

---

### 9.2 AI 对话

```
POST /ai/chat
```

**请求体：**

```
{
  "userId": 1,
  "message": "今天不想做英语了，改成数学",
  "history": [
    { "role": "user", "content": "帮我安排今天的任务" },
    { "role": "assistant", "content": "好的，你今天想重点做什么？" }
  ],
  "context": {
    "todayTasks": [
      { "id": 20, "title": "复习数学 · 知识梳理", "status": "pending" },
      { "id": 21, "title": "复习数学 · 专项练习", "status": "pending" },
      { "id": 22, "title": "英语考试准备", "status": "pending" }
    ],
    "todayCourses": [
      { "courseName": "高等数学", "startTime": "08:00", "endTime": "09:40" },
      { "courseName": "大学英语", "startTime": "14:00", "endTime": "15:40" }
    ],
    "todayRestTimes": [
      { "startTime": "12:00", "endTime": "12:30", "label": "午休" }
    ],
    "userMemories": [
      { "category": "event", "content": "用户下周要考雅思" },
      { "category": "preference", "content": "用户不喜欢超过 1 小时的任务" },
      { "category": "goal", "content": "用户想 3 个月刷完力扣 hot100" }
    ]
  }
}
```

**说明：**

- `context` 由 Java 侧现查后填充：今日任务 + 今日课程表（按当天的星期筛）+ 今天生效的休息时段 +
  用户画像记忆（`user_memory`），前端不用传；
- `userMemories` 的取法：按 `category` 分组，每组最多 3 条（按 `updated_at` 倒序取最新的），
  再合并成一个列表；只传 `category` + `content`（id / 时间不传），没有 category 或 content 的脏数据跳过；
  该用户还没有任何记忆时是**空数组**（不是 `null`）；
- 这四个列表只是给模型的参考资料，Python 侧不拿它们做任何计算；
- Python 侧只在 `userMemories` **非空**时，往 system prompt 末尾追加一段
  「你对该用户的了解」（每条一行 `- [分类] 内容`，分类：habit=学习习惯 / emotion=情绪状态 /
  event=重要事件 / preference=偏好 / goal=长期目标，未知类别用英文原值），
  并要求「不要直接罗列，与当前对话无关不要硬扯」；空画像时 system prompt 与之前完全一致。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "reply": "好的，我把英语考试准备改成了数学专项练习。",
    "toolCalls": [
      {
        "tool": "update_task",
        "args": {
          "taskId": 22,
          "title": "数学专项练习",
          "startTime": "19:00",
          "endTime": "20:00"
        }
      }
    ]
  }
}
```

---

### 9.3 AI 解析课程文本

```
POST /ai/parse-course
```

**请求体：**

```
{
  "rawText": "每周一三五 8:00‑9:40 高等数学\n每周二 14:00‑15:40 程序设计"
}
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "courses": [
      {
        "courseName": "高等数学",
        "daysOfWeek": [1, 3, 5],
        "startTime": "08:00",
        "endTime": "09:40"
      },
      {
        "courseName": "程序设计",
        "daysOfWeek": [2],
        "startTime": "14:00",
        "endTime": "15:40"
      }
    ],
    "failed": []
  }
}
```

## **说明：** 如果某行解析失败，放进 `failed` 数组，Java 返回给前端提示。

### 9.4 AI 解析今日任务意图

```
POST /ai/parse-task
```

**请求体：**

```
{
  "rawText": "今天要复习数学 2 小时，背单词 30 分钟"
}
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "tasks": [
      { "title": "复习数学", "duration": 120 },
      { "title": "背单词", "duration": 30 }
    ]
  }
}
```

---

### 9.5 AI 提炼用户画像记忆

```
POST /ai/extract-memory
```

由 Java 侧在**每次 `POST /api/ai/chat` 成功后异步调用**（线程池 `memoryExtractExecutor`），前端不直接调。

**请求体：**

```
{
  "messages": [
    { "role": "user", "content": "我下周要考雅思" },
    { "role": "assistant", "content": "那就先紧着口语练" }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| messages | array | 是 | 待提炼的对话，1-20 条；Java 目前只传本轮两条（用户这条 + AI 回复） |
| messages[].role | string | 是 | user / assistant |
| messages[].content | string | 否 | 消息内容，默认空串；至少要有一条非空白内容 |

**响应：**

```
{
  "memories": [
    { "category": "event", "content": "用户下周要考雅思" }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| memories | array | 提炼结果，最多 5 条；没有值得记住的信息时是 `[]` |
| memories[].category | string | 只有 5 类：`habit` / `emotion` / `event` / `preference` / `goal` |
| memories[].content | string | 一条记忆，第三人称描述，最长 500 字 |

**说明：**

- 只提炼、不落库：Java 拿到 `memories` 后写 `user_memory`（`user_id + category + content` 完全相同则跳过）；
- 不做记忆合并 / 遗忘、不做向量检索与语义去重、不重试、不批量 / 定时提炼；
- 请求体不合法（缺 `messages`、多传字段、全空白）返回 422；模型调用失败或返回格式不合法返回 500；
- 提炼失败**不影响**聊天主流程：Java 侧异步线程只打日志（`[记忆提炼] 用户 x 提炼失败：...`），
  不会让 `POST /api/ai/chat` 报错，也不会回滚已落库的对话；
- 落库后的记忆会在**之后每次** `/ai/chat` 时被查出来（见 §9.2 的 `context.userMemories`，
  每类最多 3 条），由 Python 注入 system prompt —— 模型因此「认识」这个用户。

---

## 10. 接口总览

| 模块 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| 认证 | POST | /api/auth/register | 注册（邮箱 + 验证码 + 密码） |
| 认证 | POST | /api/auth/login | 登录（账号 = 邮箱或用户名） |
| 认证 | POST | /api/auth/logout | 退出 |
| 认证 | POST | /api/auth/send-email-code | 发送注册验证码 |
| 认证 | POST | /api/auth/forgot-password/send-code | 找回密码：发送验证码 |
| 认证 | POST | /api/auth/forgot-password/reset | 找回密码：重置密码 |
| 任务 | GET | /api/task/today | 今日任务 |
| 任务 | POST | /api/task | 创建任务 |
| 任务 | PUT | /api/task/{id} | 修改任务 |
| 任务 | DELETE | /api/task/{id} | 删除任务 |
| 任务 | POST | /api/task/{id}/done | 标记完成 |
| 任务 | POST | /api/task/{id}/undo | 取消完成 |
| 任务 | POST | /api/task/batch | 批量添加 AI 任务 |
| 长期任务 | GET | /api/template | 列表 |
| 长期任务 | POST | /api/template | 新增 |
| 长期任务 | PUT | /api/template/{id} | 修改 |
| 长期任务 | DELETE | /api/template/{id} | 删除 |
| 长期任务 | PUT | /api/template/{id}/toggle | 启用/停用 |
| 课程 | GET | /api/course | 列表 |
| 课程 | POST | /api/course | 新增 |
| 课程 | PUT | /api/course/{id} | 修改 |
| 课程 | DELETE | /api/course/{id} | 删除 |
| 休息 | GET | /api/rest | 列表 |
| 休息 | POST | /api/rest | 新增 |
| 休息 | PUT | /api/rest/{id} | 修改 |
| 休息 | DELETE | /api/rest/{id} | 删除 |
| 统计 | GET | /api/stats/today | 今日 |
| 统计 | GET | /api/stats/week | 本周 |
| 统计 | GET | /api/stats/trend | 趋势 |
| 聊天 | GET | /api/chat/history | 历史 |
| 聊天 | POST | /api/chat/send | 发送 |
| 用户画像 | GET | /api/memory | 记忆列表 |
| 用户画像 | POST | /api/memory | 手动新增记忆 |
| 用户画像 | DELETE | /api/memory/{id} | 删除记忆 |
| 用户资料 | GET | /api/user/profile | 查询资料 |
| 用户资料 | PUT | /api/user/profile | 修改资料 |
| 用户资料 | PUT | /api/user/notifications | 通知设置 |
| 用户资料 | POST | /api/user/feedback | 意见反馈 |
| 用户资料 | GET | /api/user/onboarding-status | 新用户引导状态 |
| 用户资料 | POST | /api/user/onboarding-complete | 标记引导完成 |
| AI | POST | /ai/plan | 拆解任务 |
| AI | POST | /ai/chat | 对话 |
| AI | POST | /ai/parse-course | 解析课程 |
| AI | POST | /ai/parse-task | 解析任务 |
| AI | POST | /ai/extract-memory | 记忆提炼 |

**共 42 个接口：前端调 35 个，Java 调 Python 5 个，还有 2 个是内部辅助。**（本步新增 2 个：
`GET /api/user/onboarding-status`、`POST /api/user/onboarding-complete`）

> 记一笔：上表实际行数与这句话的口径**在本步之前就对不上**（行数比 40 多），
> 这里只按本步新增的 2 个接口做**增量**调整（40 → 42、33 → 35），没有重新盘点历史口径。

---

## 11. 几个关键说明

### 11.1 duration 由后端算

前端只传 `startTime` 和 `endTime`，`duration` 由 Java 自动算，避免前端传错。

### 11.2 planBatchId 的用途

`POST /api/task/batch` 的 `planBatchId` 是「一次 AI 拆解」的标记。以后要做：

- 撤销这次 AI 排的任务；
- 重排这次的任务；
- 对比不同版本。
  都靠这个字段。

### 11.3 toolCalls 谁来执行

**Java 执行，不是前端执行。**

```
前端 → Java /api/chat/send
         ↓
       Java 调 Python /ai/chat
         ↓
       Python 返回 reply + toolCalls
         ↓
       Java 执行 toolCalls（改数据库）
         ↓
       Java 把 reply + affectedTaskIds 返回前端
         ↓
       前端刷新任务列表
```

---

## 12. 用户资料模块

> 右上角头像那一套，外加新用户引导的状态。共 6 个接口：看资料 / 改资料（含绑定手机号、邮箱）、
> 改通知设置、提交意见反馈、查引导状态 / 标记引导完成。
> **不含改密码**（单独做）、**不做头像文件上传**
> （前端直接把 emoji 或 base64 字符串传给 `avatar`）、**不做反馈列表查询 / 已读未读 / 回复**。
> 引导**只记「完成 / 未完成」**：不记走到第几步、不记弹了几次，引导内容全在前端。
> 决策细节见 `docs/decisions.md` §12（资料 / 通知 / 反馈）与 §14（新用户引导）。

### 12.1 查询当前用户资料

```
GET /api/user/profile
```

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "username": "student01",
    "nickname": "小明",
    "avatar": null,
    "phone": null,
    "email": null,
    "onboarded": false,
    "notifyTaskReminder": true,
    "notifyCourseReminder": true,
    "notifyDailyReport": false,
    "notifySound": true
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | long | 用户 id（与认证模块一致，不叫 userId） |
| username | string | 登录名，全局唯一 |
| nickname | string | 昵称 |
| avatar | string / null | emoji 或 base64 字符串；没设置过是 `null` |
| phone | string / null | 手机号，入库前已去掉空格与短横线 |
| email | string / null | 邮箱 |
| onboarded | boolean | **新用户引导是否已完成**（库里 TINYINT 1/0，响应里 true/false）。`false` = 登录后该弹引导；列被写成 `NULL` 也按 `false` 算。登录响应（§1.2）里也带它 |
| notifyTaskReminder | boolean | 通知设置：任务提醒。**库里是 TINYINT 1/0，响应里是 true/false** |
| notifyCourseReminder | boolean | 通知设置：课程提醒（库里默认开） |
| notifyDailyReport | boolean | 通知设置：每日报告（库里默认**关**） |
| notifySound | boolean | 通知设置：提示音（库里默认开） |

**错误：**

| code | 场景 |
| --- | --- |
| 401 | 未登录或 token 失效（HTTP 状态码同样是 401，见 `docs/decisions.md` §1.1 的刻意例外） |
| 404 | 用户不存在（token 还有效，但用户已被删除） |

---

### 12.2 修改当前用户资料

```
PUT /api/user/profile
```

**请求体：** 五个字段全部可选，**不传 / `null` 表示不修改**，传空串 `""` 表示清空该字段。

```
{
  "nickname": "小明",
  "username": "student01",
  "avatar": "🐱",
  "phone": "13800138000",
  "email": "xiaoming@example.com"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| nickname | string | 否 | 不能是空白；最长 50 字符 |
| username | string | 否 | 不能是空白；最长 50 字符；与其他用户重复返回 1001 |
| avatar | string | 否 | emoji 或 base64；UTF-8 字节数 ≤ 500KB；`""` 表示清空 |
| phone | string | 否 | 5-20 位数字，可带前导 `+`；空格与短横线会被去掉后入库；`""` 表示清空 |
| email | string | 否 | 简单格式校验（有 @、有域名、有点）；最长 100 字符；`""` 表示清空 |

> `password`（改密码单独做）与 `id` **不在这里**，请求里带了也会被忽略。

**响应：** 结构同 12.1（含 4 个通知项 `notify*`），返回**更新后**的完整资料。

> 绑定 / 换绑手机号与邮箱就走这个接口（`phone` / `email` 字段），**不另开新接口**；资料校验口径见下表。

**错误：**

| code | 场景 |
| --- | --- |
| 400 | 参数不合法（空白 nickname / username、phone 或 email 格式不对、avatar 超过 500KB 等） |
| 401 | 未登录或 token 失效 |
| 404 | 用户不存在 |
| 1001 | username 已被其他用户占用 |

**说明：**

- 校验全部写在 `UserService` 里手工判断（`docs/decisions.md` §2.4），**五项字段全部校验完才写库**，不会出现「改了一半」；
- `username` 查重条件：`username = 新值 AND id != 当前 userId`，所以**原样提交自己的 username 不算重名**；
- 改 `username` **不会**让已签发的 token 失效（token 里存的是 userId，username 只是 subject），不需要重新登录；
- `avatar` 列是 **`MEDIUMTEXT`（上限 16MB）**，接口只放行 500KB，超出的统一返回 400（`docs/decisions.md` §12.10）；
- 📌 给前端记一笔：用户选本地图片时**先把图片压缩**（建议最大 400×400）再转 base64，别把几 MB 的原图直接传上来（`docs/decisions.md` §12.15）。

---

### 12.3 修改通知设置

```
PUT /api/user/notifications
```

**请求体：** 4 个开关全部可选，**不传 / `null` 表示不修改**；`true` = 开，`false` = 关。

```
{
  "taskReminder": true,
  "courseReminder": true,
  "dailyReport": false,
  "sound": true
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| taskReminder | boolean | 否 | 任务提醒 → `user.notify_task_reminder`（库里默认开） |
| courseReminder | boolean | 否 | 课程提醒 → `user.notify_course_reminder`（库里默认开） |
| dailyReport | boolean | 否 | 每日报告 → `user.notify_daily_report`（库里默认**关**） |
| sound | boolean | 否 | 提示音 → `user.notify_sound`（库里默认开） |

**响应：** 结构同 12.1（含 4 个 `notify*` 项），返回**更新后**的完整资料。

**错误：**

| code | 场景 |
| --- | --- |
| 400 | 请求体不是合法 JSON（框架级错误，返回 Spring 默认错误体而非 `Result`，见 `docs/decisions.md` §2.5） |
| 401 | 未登录或 token 失效 |
| 404 | 用户不存在（token 还有效，但用户已被删除） |

**说明：**

- **只改传了的开关**，没传的开关保持原值；4 个都不传（`{}`）等于「什么都不改」，照样返回当前资料（`updated_at` 仍会刷新）；
- 库里是 `TINYINT` 的 `1/0`，**Boolean ↔ Integer 的转换只在后端做**（`UserService`），前端只认 `true/false`；
- 与 12.2 分工明确：资料接口**不接收**通知字段，通知接口**不接收**资料字段（`nickname` / `phone` 等传了也会被忽略）；
- 通知设置**只存开关**：本步不做定时任务、不发邮件 / 短信 / 推送，开关只给前端决定要不要弹提醒。

---

### 12.4 提交意见反馈

```
POST /api/user/feedback
```

**请求体：**

```
{
  "content": "界面的按钮很好用，希望加一个番茄钟。"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| content | string | 是 | 反馈内容；不能为空 / 纯空白；trim 后 **≤ 5000 字** |

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": null
}
```

**错误：**

| code | 场景 |
| --- | --- |
| 400 | `content` 为空 / 纯空白（`反馈内容不能为空`），或超过 5000 字（`反馈内容不能超过 5000 个字符`） |
| 401 | 未登录或 token 失效 |

**说明：**

- 只往 `feedback` 表**插一条**：`user_id` 从 token 取、`content` 存 trim 后的值、`created_at` 由后端生成，**不回显内容**；
- `feedback` 表结构：`id` / `user_id` / `content`（TEXT）/ `created_at`，**只写不读**；
- 本步**不做**：反馈列表查询、已读 / 未读状态、回复、敏感词过滤、提交频率限制；
- 顺带一句：`content` 是 TEXT（上限 65535 字节），5000 字是接口层自己定的上限。

---

### 12.5 查询新用户引导状态

```
GET /api/user/onboarding-status
```

**请求参数：** 无（userId 从 token 取，不接受任何入参）。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": {
    "onboarded": false
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| onboarded | boolean | `true` = 已完成引导（不用再弹）；`false` = 未完成（登录后应该弹一次） |

**错误：**

| code | 场景 |
| --- | --- |
| 401 | 未登录或 token 失效（HTTP 状态码同样是 401，见 `docs/decisions.md` §1.1 的刻意例外） |
| 404 | 用户不存在（token 还有效，但用户已被删除） |

**说明：**

- 登录响应（§1.2）里**已经带了 `onboarded`**，正常流程不需要调这个接口；它给「刷新页面 / 引导关掉后想再确认一次」用；
- 库里是 `TINYINT`（`1` = 已完成 / `0` = 未完成），响应里是 `true/false`；列被写成 `NULL` 也按 `false` 算
  （缺省必须落在「要弹引导」这一侧）；
- 后端**只存「完成 / 未完成」一格**：不记引导走到第几步、不记弹过几次、不记完成时间（`docs/decisions.md` §14）；
- 只操作当前登录用户（userId 只可能来自 token），**不需要额外的归属校验**。

---

### 12.6 标记新用户引导完成

```
POST /api/user/onboarding-complete
```

**请求体：** 无（不需要任何参数）。

**响应：**

```
{
  "code": 0,
  "message": "success",
  "data": null
}
```

**错误：**

| code | 场景 |
| --- | --- |
| 401 | 未登录或 token 失效（HTTP 状态码同样是 401） |

**说明：**

- 把当前用户的 `user.onboarded` 置 `1`，只写 `onboarded` 与 `updated_at` 两列，其它列一个都不碰；
- **幂等**：重复调用不报错，已经完成过再调一次仍是成功（前端可以放心重试）；
- 用户不存在（token 还有效但人被删了）也返回成功 —— 幂等写没必要为此报错，后端只记一条 WARN 日志；
- **不记录**引导步骤 / 完成时间 / 弹过几次；**不改注册流程** —— 新用户靠列默认值 `0` 天然就是「未完成」；
- 老用户（加列之前就在的账号）默认值也是 `0`，下次登录 `onboarded` 为 `false`；若不想让老用户看到引导，
  手动执行一次 `UPDATE user SET onboarded = 1 WHERE ...` 即可（`docs/decisions.md` §14.10）。


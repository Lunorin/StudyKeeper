# StudyKeeper 决策记录

> 记录开发过程中的**既定规则与关键取舍**。
> 与 `docs/api.md` 冲突时 **以本文件为准**：api.md 描述接口契约，本文件是最终裁决。
> 覆盖范围：6.1 任务模块、6.2 长期任务模块（6.2.2 定时实例化前）

---

## 1. 已定规则（三条）

### 1.1 错误码 —— 以代码为准

- 资源**不存在**与资源**不属于当前用户（无权限）**，**统一返回 404**，不再区分 403 / 2001。
- 理由：不向非所有者泄露"该资源是否存在"；前端只判一个码，逻辑更简单。
- 参数不合法一律 `400`（如长期任务的 `repeatDays` 非法）。
- 现有实现：
  - `Result.error(404, "任务不存在")` —— 任务模块（PUT / DELETE / done / undo）
  - `Result.error(404, "长期任务不存在")` —— 长期任务模块（PUT / DELETE）
  - `Result.error(404, "课程不存在")` / `Result.error(404, "休息时间不存在")` —— 课程 / 休息模块
  - `Result.error(400, "...")` —— 参数非法
- 6.4.1 认证模块新增：
  - `Result.error(1001, "用户名已存在")` —— 注册重名
  - `Result.error(1002, "用户名或密码错误")` —— 登录失败（**不区分**"用户不存在"与"密码错误"，避免暴露账号是否存在）
  - `Result.error(401, "未登录")` —— JWT 拦截器校验失败，或 `/me` 对应的用户已不存在
- **⚠️ HTTP 401 是刻意例外**：认证相关的 401，**HTTP 状态码也用真的 `401`**（不是 200），这是 §2.3「业务错误 HTTP 恒 200」的**例外条款** —— 前端（axios 拦截器）习惯靠 HTTP 401 判断"清本地 token + 跳登录页"。其余业务错误仍遵循 §2.3。
- `docs/api.md` 的 `2001` / `403` 已删除（见 §5.5）；注册 / 登录响应里的用户 id 字段已由 `userId` 统一为 `id`（与 `/me` 一致）。

### 1.2 成功 message —— 以代码为准

- 所有成功响应的 `message` **统一为 `"success"`**，不使用"创建成功 / 修改成功 / 删除成功 / 已完成 / 已取消完成 / 已停用"等文案。
- 实现：`common/Result.java` 的 `SUCCESS_MESSAGE = "success"`，`success(...)` 系列固定使用。
- 前端若要提示文案，**由前端按场景自己写**，不依赖后端 message。
- **待办**：`docs/api.md` 里所有 `"xxx成功"` 的 message 示例改为 `"success"`。

### 1.3 时间格式 —— 以文档为准，代码待改

| 字段 | 目标格式 | 示例 |
| --- | --- | --- |
| `startTime` / `endTime` | `HH:mm` | `20:00` |
| `createdAt` / `updatedAt` / `completedAt` | `yyyy-MM-dd HH:mm:ss` | `2026-09-17 20:45:00` |
| 日期类 `planDate` / `date` / `weekStart` 等 | `yyyy-MM-dd` | `2026-09-17`（已符合，无需改） |

- **现状差距**：Jackson 目前用 ISO 默认格式
  - `startTime` → `"20:00:00"`（多出秒）
  - `createdAt` / `completedAt` → `"2026-09-17T22:34:38.379288"`（`T` 分隔 + 纳秒）
- **改法**：加**全局 Jackson 配置**（`Jackson2ObjectMapperBuilderCustomizer` 注册 `LocalTimeSerializer` / `LocalDateTimeSerializer` 及对应反序列化器），**不要**用 `@JsonFormat` 逐字段加 —— 容易遗漏且重复。
- **影响面**：所有返回时间字段的接口（task 系列、以及后续 course / rest / chat）。
- **已定（详见 §8.1）**：反序列化**同样强制 `HH:mm`** —— 前端必须传 `"20:00"`，`"20:00:00"` 或其他格式一律 400。
- **执行时间**：6.x 全部完成后、进入第 7 步前统一处理。

---

## 2. 通识规则（跨模块）

| # | 规则 | 说明 |
| --- | --- | --- |
| 2.1 | `userId` 硬编码为 `1` | 未做 JWT 登录，任何接口都不从 token 取值 |
| 2.2 | 统一响应 `Result<T>` | `{code, message, data}`；成功 `code=0`；失败 `data=null` |
| 2.3 | 业务错误的 HTTP 状态恒为 200 | 错误码放在 body 的 `code` 里（Controller 返回裸 `Result` 而非 `ResponseEntity`） |
| 2.4 | 暂不做全局异常处理、不做参数校验注解 | 业务校验写在 Service 里手工判断 |
| 2.5 | 框架级异常不包装 | 非法 JSON、路径变量类型错误等返回 Spring 默认错误体（非 `Result`）→ 第 7 步视情况统一 |
| 2.6 | 数据库**不加外键** | 级联行为（删模板连带删任务）在应用层显式控制，不依赖 DB |
| 2.7 | 不引入新依赖 | 仅 Spring Boot starters + MyBatis-Plus + Lombok + MySQL Driver |
| 2.8 | 事务用 Spring `@Transactional` | 随 `spring-boot-starter-jdbc` 自带；标注在 public 方法上，且必须经代理从外部调用（勿同类内部自调用） |
| 2.9 | 硬编码取值集中在 Service 常量区 | 如 `DEFAULT_USER_ID`、`STATUS_PENDING`、`SOURCE_TEMPLATE` 等 |
| 2.10 | 实体镜像表结构，接口形态差异放 DTO | 实体字段与列一一对应，不为了接口好看去改实体类型 |

---

## 3. 任务模块（6.1）既定决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 3.1 | `duration` 由后端算 | 由 `startTime` 与 `endTime` 计算，单位分钟；前端不传 |
| 3.2 | 跨天时长处理 | `minutes <= 0` 时 `+1440`。注意 `LocalTime.plusHours(24)` 是**空操作**（会回绕），必须用分钟数补偿实现 |
| 3.3 | 新建任务的服务端字段 | `userId=1`、`source=manual`、`status=pending`、`difficulty=1.00`、`templateId/planBatchId=null`；`priority` 为空兜底 `medium` |
| 3.4 | `GET /api/task/today` 统计 | `percent = Math.round(completed * 100.0 / total)`；`total=0` 时 `percent=0`（避免除零 / NaN） |
| 3.5 | 今日列表排序 | `ORDER BY start_time IS NULL, start_time, id` —— **没填时间的排最后** |
| 3.6 | `PUT /api/task/{id}` 语义 | 只改 `title/description/startTime/endTime/priority`；**请求里为 null 的字段 = 不修改**（副作用：无法通过该接口把 description 清空）；`duration` 仅在起止时间有传时重算 |
| 3.7 | done / undo 的原子性 | 同一事务内：改 `task.status`/`completed_at` + 追加一条 `completion_record`（`action=done`/`undone`），任一步失败一起回滚 |
| 3.8 | `completed_at` 置空必须显式 set | MyBatis-Plus `updateById` 默认 `NOT_NULL` 策略会**跳过 null 字段**，所以 undo 必须用 `LambdaUpdateWrapper.set(Task::getCompletedAt, null)` |
| 3.9 | done / undo 重复点击不拦截 | 前端在完成后隐藏按钮；后端照常更新并追加流水（统计只看 `task.status`，重复流水不影响正确性） |
| 3.10 | `DELETE /api/task/{id}` 为物理删除 | `completion_record` 的历史流水**保留、不级联**（库里无外键） |
| 3.11 | 归属校验 | 先按 id 查记录，再比对 `user_id=1`；不匹配按 404 处理 |

---

## 4. 长期任务模块（6.2）既定决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 4.1 | `repeat_days` 存储 | 库里是字符串 `"1,3,5"`；**实体用 `String`**（直接映射 `varchar(20)`，零额外机制），**DTO 用 `List<Integer>`**（对外是数组） |
| 4.2 | 转换位置 | `String ↔ List<Integer>` 的私有方法放在 `TaskTemplateService`（`parseRepeatDays` / `formatRepeatDays` / `toDTO`）。6.2.2 若在别处也要用，再提取到 `common` |
| 4.3 | 写入规范化 | 去重 + 升序后再入库，`[5,3,3]` → `"3,5"`。避免同一语义出现 `"3,1,5"` / `"1,3,5"` 两种写法 |
| 4.4 | 读取宽容 | 解析时跳过空串 / 非数字 / 越界 token，不抛异常 —— 一行脏数据不能让整个列表接口 500 |
| 4.5 | `repeatDays` 校验 | 至少一个、取值 1–7（周一=1 … 周日=7，用 `DayOfWeek.getValue()`） |
| 4.6 | 校验失败如何返回 | Service 抛 `IllegalArgumentException`，Controller **本地 try/catch** → `Result.error(400, msg)`。以后加全局异常处理时，删掉这两处 try/catch 行为不变 |
| 4.7 | `active` 类型 | 实体 `Integer`(1/0)、DTO `Boolean`；用 `Boolean` 放在实体上会有「NULL 被当成 false」的歧义 |
| 4.8 | `priority` 兜底 | 请求未传时**在代码里显式兜底 `medium`**。若只依赖表默认值，MyBatis-Plus 会因跳过 null 字段而让 DB 存 `medium`、返回体却是 `null`（库与响应不一致） |
| 4.9 | 其他默认值 | `difficulty=1.00`、`active=1`（都在代码里显式赋值，保证返回体完整） |
| 4.10 | `PUT /api/template/{id}` 语义 | 只改 `title/description/duration/priority/repeatDays`；**不允许改** `userId`/`active`/`difficulty`；`null`=不修改，`[]`=非法(400) |
| 4.11 | 本阶段无「启用 / 停用」接口 | 文档 §3.5 的 `PUT /api/template/{id}/toggle` 尚未实现（实体 / DTO 已预留 `active`），前端需要时再补 |
| 4.12 | `POST /api/template` 手动实例化 | 模板存好后，若今天命中 `repeatDays` 就立刻生成一条今日 task（`source=template`、`status=pending`，`title/description/duration/priority/difficulty` 从模板复制，`templateId`=新模板 id，`planDate`=今天）；同模板同一天已存在则**跳过**（幂等）；与模板插入同事务 |
| 4.13 | `DELETE /api/template/{id}` 级联规则 | **先删该模板生成的、`status='pending'` 的 task，再删模板**；`done` / `missed` 作为历史保留；两步同事务 |
| 4.14 | 删除前置校验 | 模板不存在或不属于 `user_id=1` → 直接 `false`，**不删任何东西**（提前 return 保住其下任务） |
| 4.15 | 无外键 | 不加 DB 外键，级联由 4.13 的应用层逻辑实现 |
| 4.16 | 列表包含已停用模板 | `GET /api/template` 返回当前用户**全部**长期任务（含 `active=0`），由前端自行过滤 |
| 4.17 | **懒加载实例化**（取代 §5.6 的定时方案） | `GET /api/task/today` 在查询前先调 `TaskTemplateService.instantiateForToday(userId)`：取该用户 `active=1` 的模板（id 升序），逐个按 §4.12 的单模板规则补生成当天实例；今天没命中 `repeatDays` 或当天已有该模板实例的都跳过（幂等），因此**不引入 `@Scheduled`**。**代价**：今天手动删掉的模板实例任务，下次打开今日页会被**重新生成**（去重键只有 `template_id + plan_date`）；要表达「今天不做」应改为停用模板。`repeat_days` 里的脏 token 由 `parseRepeatDays` 忽略，不会让今日任务接口失败 |

---

## 5. 待 6.x 完成后统一处理（进入第 7 步前）

| # | 事项 | 说明 |
| --- | --- | --- |
| 5.1 | **时间格式全局 Jackson 配置** | 见 1.3，影响所有返回时间字段的接口 |
| 5.2 | ~~反序列化是否强制 `HH:mm`~~ **已定** | 强制 `HH:mm`，见 §8.1；将来做全局配置时，入参也要注册严格的反序列化器 |
| 5.3 | `GET /api/task/today` 的 `tasks[]` 字段集 | 文档 §2.1 只列 `id/title/description/startTime/endTime/duration/priority/status/source/templateId/completedAt`，**代码目前直接返回整个 Task 实体**（多出 `userId/planDate/difficulty/createdAt/updatedAt`）。是否按文档裁剪成专用响应 DTO —— **待确认** |
| 5.4 | 全局异常处理（可选） | 统一框架级异常（非法 JSON / 类型不匹配）为 `Result` 包装；做完可删掉 4.6 的 try/catch |
| 5.5 | ~~`docs/api.md` 同步修改~~ **已完成**（2026-09-17） | 已删掉 `2001` / `403`（§0.4 错误码表、§2.3 错误表），12 处成功 message 已全部改为 `"success"` |
| 5.6 | ~~6.2.2 定时自动实例化~~ **已按懒加载实现**（见 §4.17） | 复用 `TaskTemplateService.instantiateForToday(userId)`，遍历所有 `active=1` 模板；触发点放在 `GET /api/task/today` 之前，**不引入 `@Scheduled`** |
| 5.7 | 统一 Maven 本地仓库 | 见 7.3，避免两份依赖副本与版本漂移 |

---

## 6. 验证约定（自 6.1 起沿用）

- 任何改动都要**实跑验证**：编译 → 起服务 → curl 真实调用 → 查库核对，不能只靠"看着对"。
- 业务接口用 curl 时，**body 走文件**（`--data-binary @file`）：PowerShell 会吞掉内联 JSON 的引号，导致误判为 400。
- 中文用 `\uXXXX` 转义写入请求体，规避控制台编码问题。
- **事务回滚实证方法**：临时给目标表加 `BEFORE INSERT/DELETE` 触发器 `SIGNAL SQLSTATE '45000'` 强制某一步失败 → 验证另一步是否回滚 → **立即 DROP 触发器**并确认 `information_schema.TRIGGERS` 归零。
- 测试数据统一用 `FIX-` 前缀，测完 `DELETE ... WHERE title LIKE 'FIX-%'` 清理，**不碰真实数据**。
- 本地测试起服务用 **8081**（8080 常被 IDEA 里启动的实例占用）；测试实例用完即停。
- 每步完成后检查 IDE 的 file problems，保持无 error、无 warning。

---

## 7. 环境与依赖备忘

| # | 项 | 说明 |
| --- | --- | --- |
| 7.1 | 技术栈 | Spring Boot **3.5.16**、MyBatis-Plus **3.5.17**（`mybatis-plus-spring-boot3-starter`）、Lombok、`mysql-connector-j`（版本由 Boot BOM 管理）；`java.version=17` |
| 7.2 | 为何从 Boot 4.1.1 降级 | MyBatis-Plus **没有 Boot 4 的 starter**（`mybatis-plus-spring-boot4-starter` 在 Maven Central 上不存在），其 `boot3-starter` 依赖 `spring-boot-dependencies:3.5.9` |
| 7.3 | 两个 Maven 本地仓库 | IDEA 用 `D:\Application\apache-maven-3.6.1\mvn_repo`，命令行 `mvnw` 用 `C:\Users\16010\.m2\repository`（建议后续统一） |
| 7.4 | 构建命令 | 用 `.\mvnw.cmd`（wrapper 为 Maven 3.9.16）；本机 `mvn` 是 3.6.1，低于 Boot 3.5 要求的 3.6.3+ |
| 7.5 | JDK 17 位置 | `C:\Users\16010\AppData\Local\Programs\Microsoft\jdk-17.0.19.10-hotspot` |
| 7.6 | 数据库 | MySQL 8.0.34，库名 `study_agent`，`root/123456`，JDBC 串带 `serverTimezone=Asia/Shanghai` |
| 7.7 | 改完代码要重启 IDEA 实例 | 正在运行的实例不会自动加载新 class，否则前端会打到旧代码 |

---

## 8. 课程表模块（6.3）既定决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 8.1 | `startTime` / `endTime` **严格 `HH:mm`** | DTO 里时间字段用 `String`（不是 `LocalTime`），Service 用 `DateTimeFormatter.ofPattern("HH:mm")` 解析；**不符合 `HH:mm` 一律 400** —— `"08:00:00"`、`"8:00"` 都会被拒绝，前端必须传 `"08:00"`。返回时同样格式化成 `HH:mm` |
| 8.2 | **不支持跨天** | 校验 `startTime < endTime`（两者相等也算非法），不合法返回 400 `startTime 必须早于 endTime`。与任务模块的跨天 `+1440` 处理**刻意不同**，课程场景不允许 23:00→00:30 |
| 8.3 | 本模块天然满足 §1.3 | DTO 时间是 `String`，不经过 java.time 序列化器，所以将来加全局 Jackson 配置时**本模块不受影响，也不会被双重格式化** |
| 8.4 | 400 的文案 | `dayOfWeek 只能是 1-7 的数字`；`startTime` / `endTime` `不能为空`；`... 格式必须是 HH:mm，例如 08:00`；`startTime 必须早于 endTime` |
| 8.5 | PUT 用「合并后」的值校验 | 只传 `startTime` 时与库里已有的 `endTime` 比较；校验不通过时**不产生任何写入** |
| 8.6 | 排序 | `day_of_week ASC, start_time ASC`，追加 `id ASC` 兜底，保证同一时刻顺序稳定 |
| 8.7 | 404 文案 | `课程不存在`（错误码统一 404，文案按模块区分，与 `任务不存在` / `长期任务不存在` 一致） |
| 8.8 | 归属校验沿用既有模式 | PUT：`selectById` + 比对 `user_id=1`；DELETE：wrapper `eq(id)+eq(userId)`；GET 只查 `user_id=1` |
| 8.9 | `GET /api/course?dayOfWeek=` | 可选参数，不传返回全部，**不做范围校验**（传 9 只返回空列表）；一次只加一天，前端选多天就循环调用（同文档 §4.2） |

---

## 9. 休息时间模块（6.3.2）既定决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 9.1 | `day_of_week` 可空 | 库里是 `tinyint DEFAULT NULL`；**null = 每天生效**。DTO 里 `dayOfWeek` 用 `Integer`（可空），与课程表模块的必填 `dayOfWeek` 不同 |
| 9.2 | 列表排序 = **每天最前** | 实现为 `ORDER BY day_of_week IS NULL DESC, day_of_week ASC, start_time ASC, id ASC`。**注意**：需求里给的 `ORDER BY day_of_week IS NULL, ...` 实际效果是"每天排最后"（MySQL 里 `NULL IS NULL` = 1、`1 IS NULL` = 0，ASC 时 0 在前），与需求括号注释"每天生效的排最前"及文档 §5.1 示例相反，故按注释意图实现。**若要字面 SQL 的效果（每天最后），去掉 `DESC` 即可** —— 此点待最终确认 |
| 9.3 | PUT 无法改回「每天」 | 既有约定"null = 不修改"与 `dayOfWeek` 的 null 兼作合法值（每天）冲突。选择保持"null = 不修改"（避免 `PUT {label}` 意外把周几清成每天）。**代价**：要把记录改回"每天"只能 DELETE 后重新 POST。可选改法：(a) PUT 里 `dayOfWeek` 总是生效（代价：前端每次都必须传该字段）；(b) 加显式开关字段 |
| 9.4 | 严格 `HH:mm` + 不支持跨天 | 与 §8.1 / §8.2 完全一致：`"10:00:00"` / `"8:00"` 一律 400；`startTime < endTime`，两者相等也算非法 |
| 9.5 | POST 省略 `dayOfWeek` | **合法**，等同传 null（每天）—— 这是与课程表模块的关键差异（那边 `dayOfWeek` 必填） |
| 9.6 | 400 的文案 | `dayOfWeek 只能是 1-7 的数字，或留空表示每天`；`startTime` / `endTime` `不能为空`；`... 格式必须是 HH:mm，例如 10:00`；`startTime 必须早于 endTime` |
| 9.7 | 404 文案与归属校验 | `休息时间不存在`；PUT 用 `selectById` + 比对 `user_id=1`，DELETE 用 wrapper `eq(id)+eq(userId)`，GET 只查 `user_id=1` |
| 9.8 | 本模块天然满足 §1.3 | 同 8.3：DTO 时间是 `String`，不经过 java.time 序列化器，将来加全局 Jackson 配置时不受影响 |
| 9.9 | 与课程模块的 helper 重复（技术债） | `requireTime` / `formatTime` / `requireStartBeforeEnd` 在 `CourseService` 与 `RestTimeService` 各有一份（当时约定"不动课程表模块代码"，未抽公共类）。**待办**：提取到 `common/TimeUtils` |

---

## 10. 统计模块（6.3.3）既定决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 10.1 | 统计口径 | 只统计 `user_id = 1`；**只看 `task` 表，不看 `completion_record`**；`completed` 指 `status = 'done'`；用 **`plan_date`** 判断任务属于哪一天（不用 `created_at`） |
| 10.2 | 本周 = 周一 ~ 周日 | 从 `LocalDate.now().with(DayOfWeek.MONDAY)` 到 `+6` 天；等价于 `plan_date BETWEEN 周一 AND 周日`（`plan_date` 是 DATE，不需要时间部分）。**文档 §6.2 的示例日期 `2026-09-15 ~ 2026-09-21` 是周二~周一，与规则不符、示例有误**（2026-09-17 是周四，真实本周应为 `2026-09-14 ~ 2026-09-20`），建议把文档那两行示例日期改掉 |
| 10.3 | `percent` 公式 | `total = 0` 时 0，否则 `Math.round(completed * 100.0 / total)`，与 §3.4 完全一致 |
| 10.4 | trend 语义 | 含**今天在内**的连续 N 天，按日期**升序**；**没有任务的日子也要返回，`completed = 0`**；`days` 默认 7 |
| 10.5 | `days` 越界 → 400 | 范围 1-30，越界抛 `IllegalArgumentException` → Controller 本地 try/catch → `Result.error(400, "days 只能是 1-30 的数字")`（沿用各模块的既有模式）。若想改成静默钳制到 1/30，需小改 |
| 10.6 | trend 的实现方式 | 一次 `selectList` 查出区间内所有已完成任务 → Java 按 `planDate` 分组 → 再补齐 N 天。**不用**"每天一次 count"，也**不用** `selectMaps`（避免 `java.sql.Date` 等 JDBC 类型转换坑）；区间 ≤ 30 天，数据量可忽略 |
| 10.7 | trend 的返回类型 | 用 `TrendItemDTO`（`date` + `completed`），不用 `List<Map<String, Object>>`，保证字段名与字段顺序稳定 |
| 10.8 | 只读模块 | `StatsService` 只注入 `TaskMapper` 做查询：**不写库、无事务、无写操作的归属校验**；三个接口都没有资源 id，因此**没有 404 分支** |
| 10.9 | 与任务模块的口径关系 | `/api/stats/today` 与 `/api/task/today` 的 `total/completed/percent` 口径完全相同（同 §3.4），理论上这三个数**永远相等**，可互相交叉校验 |

---

## 11. 认证模块（6.4.1）既定决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 11.1 | 方案 = JWT + 自定义拦截器 | **不用 Spring Security 那一整套**（无 `@EnableWebSecurity`、无 `SecurityFilterChain`）；只引 `spring-security-crypto`（拿 `BCryptPasswordEncoder`）与 `jjwt` 0.12.5（api / impl / jackson） |
| 11.2 | token 参数 | `secret` 与 `expiration` 从 `application.yml` 的 `jwt.secret` / `jwt.expiration` 读；HS256 签名；`subject` = username，另带 `userId` claim；`exp - iat = expiration`（默认 7 天 = 604800 秒） |
| 11.3 | **secret 启动校验** | `JwtUtil` 的 `@PostConstruct` 里校验，**按「字节数」要求 ≥ 32**（HS256 需 256 bit；中文在 UTF-8 下是 3 字节/字符，按字符数校验会误放行）；不满足直接抛 `IllegalStateException`，**启动即失败**，而不是等第一次登录才报错。报错文案：`jwt.secret 至少需要 32 字节（HS256 要求 256 位密钥），当前只有 N 字节` |
| 11.4 | **jjwt 数值 claim 的坑** | JSON 里的小整数会被反序列化成 `Integer`，直接 `claims.get("userId", Long.class)` 会抛异常；必须用 `Number` 接再 `longValue()`（代码注释里已写明） |
| 11.5 | 拦截器范围 | `JwtInterceptor` 拦 `/api/**`，**只放行 `/api/auth/register` 与 `/api/auth/login`**；`/api/auth/logout`、`/api/auth/me` **也要 token**。`OPTIONS` 预检请求直接放行（跨域时浏览器发的预检不带 `Authorization`，拦了会导致跨域失败） |
| 11.6 | 校验通过后怎么传 userId | `request.setAttribute("userId", userId)`（常量 `JwtInterceptor.USER_ID_ATTRIBUTE`）；`/me` 用 `@RequestAttribute("userId")` 取。**不给现有接口加 `@RequestAttribute`**（那是 6.4.2 的事） |
| 11.7 | 401 的写法 | `preHandle` 阶段直接往 `HttpServletResponse` 写 JSON：**HTTP 401** + `Result.error(401, "未登录")`，`Content-Type: application/json;charset=UTF-8`。**ObjectMapper 必须用容器里注入的那个**（`new ObjectMapper()` 会丢掉 Boot 的 Jackson 配置）。HTTP 401 是 §1.1 记录的**刻意例外** |
| 11.8 | 错误码语义 | 注册重名 → `1001 用户名已存在`；登录失败 → `1002 用户名或密码错误`（**用户不存在与密码错不区分**，避免暴露账号是否存在）；`/me` 时用户已不存在 → `401 未登录` |
| 11.9 | 用户 id 字段统一为 **`id`** | 注册、登录、`/me` 三个响应都用 `id`（不再用 `userId`）。`docs/api.md` §1.1 / §1.2 已同步；**§8.1 / §8.2（Java→Python 请求体）里的 `userId` 属于另一套契约，未改** |
| 11.10 | 密码存储 | `BCryptPasswordEncoder` 作为 **`@Bean` 单例**（放在 `WebConfig`，`AuthService` 构造器注入），默认 strength 10；库里存 `$2a$10$...` 哈希。**不要**既声明 `@Bean` 又手动 `new` |
| 11.11 | nickname 规则 | 注册时 nickname 为空/空白 → **默认等于 username**（文档 §1.1 的规定） |
| 11.12 | 对前端的影响 | **21 个业务接口从此全部需要 token**，前端必须登录后带 `Authorization: Bearer <token>`，否则全线 401；但业务接口内部**仍硬编码 `userId = 1`**（6.4.2 才改成从 token 取） |
| 11.13 | **`user_id = 1` 与测试账号的对应关系（对 6.4.2 关键）** | `user` 表原本为空，验证时注册的 **`student01` 拿到了 `id = 1`**（已保留；密码 `123456`，nickname 小明），`student02` 已删。**现有业务数据全部是 `user_id = 1`**，所以 6.4.2 用 `student01` 登录即可看到全部历史数据。若要换成别的账号名，应 **`UPDATE user SET username/nickname ... WHERE id = 1`**（保持 id 不变）；**直接删掉 student01 而不把 `AUTO_INCREMENT` 重置为 1，下个账号会拿到 id=3，就和现有数据对不上了** |
| 11.14 | 本步**明确不做** | refresh token、角色/权限、密码长度与格式校验（BCrypt 只取前 72 字节；`password` 为 `null` 时 `encode(null)` 抛 `IllegalArgumentException` → 500）、注册并发竞态（先查后插，极端情况撞 `UNIQUE(username)` → 500）、`jwt.secret` 外置到环境变量（现在明文写在 yml；**改它会让所有已发 token 立即失效**） |

---

## 12. 用户资料模块（右上角头像 · 第 1 步）既定决策

> 目标：`user` 表加 `avatar / phone / email`，新增 `GET /api/user/profile` 与 `PUT /api/user/profile`。
> **本步只做 user 资料**：不做改密码、不做通知设置 / 意见反馈（第 2 步）、不做头像文件上传。

| # | 决策 | 说明 |
| --- | --- | --- |
| 12.1 | 只改 `user` 表的列，不加新表 | DDL：`ALTER TABLE user ADD COLUMN avatar TEXT, ADD COLUMN phone VARCHAR(20), ADD COLUMN email VARCHAR(100)`。**不加外键、不加新索引**；`username` 原有的 `UNIQUE` 索引正好给查重兜底 |
| 12.2 | 实体直接加字段 | `User` 加 `avatar / phone / email`（§2.10：实体镜像表结构，不为了接口好看改类型）。**auth 模块一行没动**：`AuthController` / `AuthService` / `JwtUtil` / `JwtInterceptor` / `WebConfig` 全部保持原样，`/api/auth/me` 仍只返回 `id / username / nickname` |
| 12.3 | 新接口走新模块 | 新建 `controller/UserController`（`/api/user`）+ `service/UserService`，与 `AuthService` 分开，避免以后加改密码 / 通知设置时互相牵连。该路径本来就被 `JwtInterceptor` 的 `/api/**` 覆盖，**`WebConfig` 不用改** |
| 12.4 | userId 来源 | `@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE)` 从 token 取，不再硬编码 1（§11.6 的正式落法） |
| 12.5 | 请求体语义 | `UpdateProfileRequest` 五个字段全部可选：**null / 不传 = 不改，空串 = 清空**（沿用课程 / 休息「只传要改的字段」的做法）。MyBatis-Plus `updateById` 默认忽略 null 字段，正好配合这套语义 |
| 12.6 | 错误码 | 参数不合法 → `400`；用户不存在 → `404 用户不存在`；**username 与其他用户重复 → `1001 用户名已存在`**（复用注册的码，不新增码）。查重条件 `username = 新值 AND id != userId` —— 排除自己，所以「原样提交自己的 username」不算重名（还顺带省掉那次 SQL） |
| 12.7 | 校验写在 Service（§2.4） | 手工校验、不加校验注解、不做全局异常处理。为了把「重名（1001）」和「参数错（400）」分开，新增 `common/UsernameExistsException`（仿 `AiToolCallException` 的写法：message 可直接展示给前端），Controller 里 `catch` 后转 1001 |
| 12.8 | 先校验、后写库 | 五项资料全部校验通过才 `updateById`，任何一项不合法都抛异常、**不产生半截写入**（不会出现「前几个字段改了、后几个没改」） |
| 12.9 | avatar 的形态与上限 | 前端直接传 emoji 或 base64 字符串，**服务端不做文件上传 / 不落磁盘 / 不校验内容**；服务端只按 **UTF-8 字节数 ≤ 500KB（512000）** 拦恶意超长。列类型是 `MEDIUMTEXT`（16MB），所以这道 500KB 就是实际生效的天花板（见 §12.10） |
| 12.10 | ✅ `avatar` 列已改成 `MEDIUMTEXT`（**方案 1，2026-09-20 拍板并执行**） | 原 DDL 给的 `TEXT` 上限只有 **65535 字节**，与 500KB 的应用上限冲突（实测 PUT 70KB 的 avatar → 写库失败 → HTTP 500）。已执行 `ALTER TABLE user MODIFY COLUMN avatar MEDIUMTEXT;`（上限 **16MB**），**只改了 avatar 一列，其它列一行没动**；现在 DB 这层不会再拒，超过 500KB 的入参统一由 `UserService` 拦成 400 |
| 12.11 | phone / email 归一化与校验 | phone：允许写空格 / 短横线，**入库前去掉**（`138-0013-8000` → `13800138000`），最终只能是 5-20 位数字 + 可选前导 `+`；email：只做「有 @、有域名、有点」的简单校验 + 长度 ≤ 100，**不做验证码、不做唯一性约束** |
| 12.12 | 改 username 对 token 无影响 | token 的 subject 是 username、claim 里带 userId，`JwtInterceptor` 只校验签名与过期，**所以改完用户名不用重新登录**（前端本地缓存的名字自己刷新即可） |
| 12.13 | 与第 2 步的边界 | 通知设置、意见反馈**不在本步**；改密码也不在（BCrypt 相关逻辑留在 `AuthService`）；前端头像只传字符串，不做上传 / 裁剪 / CDN |
| 12.14 | 本步**明确不做** | 改密码、头像文件上传与存储、通知设置、意见反馈、username 并发竞态（先查后插，极端情况撞 `UNIQUE(username)` → 500，同 §11.14）、avatar 内容合法性校验、`updated_at` 之外的审计字段 |
| 12.15 | 记一笔：头像图片要**在前端压缩**（**不在本步做**） | 用户选本地图片时，前端应先把图片压到小尺寸（建议最大 **400×400**）再转 base64 传给 `avatar`；**不要把几 MB 的原图 base64 后直接塞过来**（费流量，响应体也会跟着膨胀）。后端本步只负责 500KB 上限，压缩 / 裁剪属于前端步骤 |

---

## 13. 验证码邮件改为异步发送（2026-09-22）既定决策

> 起因：邮箱验证码发送慢 —— 后端日志显示邮件确实发了，但 SMTP 耗时超过 **10 秒**，前端 10 秒超时，
> 用户看到的是「网络不太好」。**只改后端**（前端一行没动：它本来就在等，只是后端返回变快了）。

| # | 决策 | 说明 |
| --- | --- | --- |
| 13.1 | 生成与发信拆成两步 | `EmailCodeService.generateCode(email)` 只做「校验 + 生成 6 位码 + 存内存」并**返回 code**；`EmailCodeService.sendEmail(email, code)` 只发信。旧的 `generateAndSend` **删除**（避免留一条「会发信」的旧路径） |
| 13.2 | 异步入口必须**单独一个类** | `service/EmailSender.sendAsync(email, code)` 加 `@Async(AsyncConfig.EMAIL_EXECUTOR_BEAN_NAME)`；`@Async` 靠 Spring 代理生效，**同类内部自调用会静默退化成同步**，所以它不能塞进 `EmailCodeService`，只能由别的 Bean（`AuthController`）调用 |
| 13.3 | 线程池 | 复用 `config/AsyncConfig`（已有 `@EnableAsync`），新增 `emailExecutor`：核心 **2** / 最大 **4** / 队列 **100** / 线程名前缀 **`email-send-`**；拒绝策略 `CallerRunsPolicy`（队列满时让调用线程自己发 —— 不丢信优先，极端情况这一次响应会退化成同步）；停机等 10 秒收尾（接口早已回 200，不能把在飞的邮件掐断） |
| 13.4 | 接口语义 | `POST /api/auth/send-email-code` 与 `POST /api/auth/forgot-password/send-code`（含别名 `/forgot-password`）都只等「生成 + 存内存」就返回 200，**不等 SMTP**；两个接口共用同一套异步套路（注册那条在 Controller 里直接 `generateCode` + `sendAsync`，找回密码那条先过 `AuthService.generateForgotPasswordCode`（校验邮箱已注册）再 `sendAsync`） |
| 13.5 | **不给这两个接口加 `@Transactional`** | 发信在异步线程里，事务也管不到它；加了只会把「生成验证码」也圈进事务、白占一个连接 |
| 13.6 | 同步阶段仍会 400 的三件事 | 邮箱格式不正确 / 60 秒内重复发 / **没配发件人**（`MAIL_USERNAME` 为空）。最后这条刻意**留在同步阶段**：配置错当场就能发现，丢给异步线程的话用户会拿到「成功」却永远收不到信 |
| 13.7 | 发信失败怎么处理 | 只 `log.error`（`EmailSender` 把异常全吞掉），**不抛、不重试、不发队列、也不撤掉内存里那条验证码**。代价：用户拿到的是「发送成功」，但 60 秒限流照样拦着他 —— 只能等一会儿再重发。**与旧版行为不同**：旧版发信失败会把刚存的记录撤掉（可以立刻重试）；现在不撤，因为「一失败就重置限流窗口」等于把防刷关掉（SMTP 挂着时用户点几次就是几次连接） |
| 13.8 | 明确不做 | 重试机制、邮件队列（MQ / 落库重投 / 定时补发）、改 SMTP 配置（`spring.mail.*` 的 host / port / SSL / 授权码一律不动）、引入新依赖（线程池用 Spring 自带的 `ThreadPoolTaskExecutor`）、前端改动 |
| 13.9 | 验证方式 | `PasswordStrengthLightTempTests` 新增 `asyncSendReturnsImmediatelyAndRunsOnEmailExecutorThread`：假 JavaMailSender 睡 500 毫秒（代表 10 秒级 SMTP），起一个只注册 `AsyncConfig` + `EmailCodeService` + `EmailSender` 的极小 `AnnotationConfigApplicationContext`（不连库 / 不起 web），断言「1) `sendAsync` 200 毫秒内返回；2) 邮件最终由 `email-send-*` 线程真的发出去；3) 邮件里的码 = 内存里的码」。其余用例里 `@Async` 不生效（裸 `new` 的 `EmailSender`）—— 正好当同步调用用，断言拿得到邮件 |

---

## 14. 新用户引导（只做后端）既定决策

> 目标：`user` 表加 `onboarded`，登录响应带上它，并提供「查状态 / 标记完成」两个接口。
> **只做后端**：引导长什么样、分几步、什么时候弹、能不能跳过，全是前端的事（**前端一行没动**）。

| # | 决策 | 说明 |
| --- | --- | --- |
| 14.1 | DDL | 已执行 `ALTER TABLE user ADD COLUMN onboarded TINYINT DEFAULT 0;`。**只有这一列**：不加新表、不加步骤表、不加完成时间列。MySQL 加列时把已有行填成默认值 `0`（见 §14.10） |
| 14.2 | 实体用 `Integer`，对外才是 `Boolean` | 沿用 §2.10 的老套路：`User.onboarded` 是 `Integer`（镜像 TINYINT 1/0），`UserProfileDTO.onboarded` / `LoginResponse.onboarded` / `OnboardingStatusDTO.onboarded` 都是 `Boolean`。**转换只写在两处**：`UserService.toOnboarded(Integer)`（用于 profile / 状态接口）与 `AuthService.login` 里的那一行（用于登录响应） |
| 14.3 | 登录响应 = **方案 A**（加字段，不做方案 B） | 选 A：登录后立刻能判断要不要弹引导，**省一次请求**（方案 B 要前端再调 `/api/user/profile`）。但**不把 `LoginResponse` 换成 `UserProfileDTO`** —— 前端登录流程认的是 `data.token` 与 `data.id/username/nickname`，整体换结构会直接把登录打挂；只**新增** `onboarded` 一个字段是向后兼容的（前端旧代码忽略它即可） |
| 14.4 | **认证流程一行没动** | `JwtUtil` / `JwtInterceptor` / `WebConfig` / 注册 / 找回密码全部保持原样。`AuthService.login` 只多了一行「取 `user.getOnboarded()` 转 Boolean 塞进 `LoginResponse`」，**token 的生成与校验完全没变**；`AuthService.register` 一个字没改 |
| 14.5 | 新接口放哪 | 放 `controller/UserController`（`/api/user/**`）：这两个接口操作的是「当前登录用户的资料」，和 profile / notifications 摆一起最自然。该路径已被 `JwtInterceptor` 的 `/api/**` 覆盖，**`WebConfig` 不用改**（也不用像 auth 那样加放行） |
| 14.6 | userId 来源与归属校验 | `@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE)`，与 §12.4 一致 —— userId 只可能来自 token，**接口没有任何 userId 入参**，所以不存在「标记别人的引导」这种可能，**不加额外归属校验** |
| 14.7 | `GET /api/user/onboarding-status` | 返回 `{ "onboarded": true/false }`（新建单字段 DTO `OnboardingStatusDTO`，照旧裹在 `Result` 里）；用户不存在（token 还有效但人被删了）→ `404 用户不存在`，与 §12 其它接口同一口径。**列被写成 NULL 也按 `false`（未完成）算** —— 缺省必须落在「要弹引导」这一侧，所以 `toOnboarded` 没有照抄通知开关的 `toBoolean`（那个 null → null） |
| 14.8 | `POST /api/user/onboarding-complete` | 无请求体、无参数；把 `onboarded` 置 `1` 并刷新 `updated_at`（`updateById` 只带这两列，跳过 null 字段），返回 `Result.success()`（`data: null`）。**幂等**：重复调用不报错；用户已不存在时也返回成功（幂等写没必要为「token 有效但人被删了」报错），只留一条 WARN 日志 |
| 14.9 | **注册流程不动** | 注册**不显式写这一列**：MyBatis-Plus 的 insert 跳过 null 字段 → 落库拿到列默认值 `0` = 未完成，正好是新用户该有的状态。不需要给 `User` 加 `@TableField`，也不需要改 `AuthService.register` |
| 14.10 | 记一笔：老用户会看到引导 | 加列时已有行被填成 `0`，所以**改造前的老账号下次登录 `onboarded` 也是 `false`**（会看到引导）。这是「默认值就是 0」的直接结果；若不想让老用户看引导，**手动执行一次** `UPDATE user SET onboarded = 1 WHERE id IN (...)` 即可。后端**不做**「按注册时间自动判老用户」的逻辑（那要加列 / 加规则，超出本步） |
| 14.11 | 本步**明确不做** | 引导内容 / 步骤记录 / 进度百分比、弹过几次、跳过与稍后再看、引导完成时间列、老用户自动回填、前端任何改动、新依赖（Lombok / MyBatis-Plus / slf4j / Jackson 都是现成的）；也没做缓存——`onboarded` 是个一格布尔值，读一次库最省心 |

---

## 15. Docker 化（本地 compose 跑通 4 个服务）既定决策

> 目标：`docker compose up -d --build` 一条命令把 mysql / backend / ai / nginx 四个服务全跑起来。
> **只加 Docker 相关文件**（3 个 Dockerfile + 3 个 .dockerignore + docker-compose.yml + nginx/nginx.conf
> + mysql/init/01-schema.sql + .env(.example) + 根 .gitignore）。
> 业务代码一行没动；唯一的源码改动是 `application.yml` 的三处 `${ENV:默认值}`（默认值 = 原来的本机直连配置）。

| # | 决策 | 说明 |
| --- | --- | --- |
| 15.1 | 四个服务，**只有 nginx 对外** | mysql / backend / ai 只用 `expose`（compose 内网），宿主机只映射 nginx 的 `80:80`；容器之间用**容器名**互访：后端连 `mysql:3306`、调 AI 用 `http://ai:8000`（container 里的 localhost 指它自己） |
| 15.2 | 前端 = **方案 B 的简化版** | 不单独跑「前端 nginx」再让主 nginx 反代过去（那要多一层容器）。`study-agent-frontend/Dockerfile` 建出来的镜像**本身就是 nginx + dist**，它直接充当 compose 里的 `nginx` 服务，站点配置用 `nginx/nginx.conf` 挂载覆盖。相比「前端只产 dist + 主 nginx 挂宿主目录」：少一步手工构建，也不会因为忘了重建而跑旧前端 |
| 15.3 | `nginx/nginx.conf` 写的是 **server 块** | 它挂到容器里的 `/etc/nginx/conf.d/default.conf`（站点级配置），不是带 events/http 的完整 nginx.conf —— 这样不用跟着 nginx 版本维护整份配置，改完 `docker compose restart nginx` 即生效 |
| 15.4 | `/api/` 反代 **必须关缓冲** | `proxy_pass http://backend:8080`（末尾不带路径，URI 原样透传）。`proxy_buffering off` 专门给 SSE：`POST /api/ai/chat/stream` 是 `text/event-stream`（SseEmitter 最多挂 5 分钟），开着缓冲前端会「等结束才一次性收到」；配套 `Connection ""` 与 `proxy_read_timeout 300s`（> 后端 30s AI 超时、= 前端 SSE 上限） |
| 15.5 | 前端跨域 = 不需要 | 前端 axios 的 baseURL 是相对路径 `/api`（开发时靠 vite devServer 代理），线上由 nginx 同源反代 —— 不用配 CORS，也不用给前端传任何 `VITE_*` 构建变量（所以前端 Dockerfile 里没有 ARG/ENV） |
| 15.6 | 数据库**结构**打哪来 | 仓库里原本没有任何 .sql。已用本机 `study_agent` 的 `mysqldump --no-data` 生成 `mysql/init/01-schema.sql`（9 张表，**只有结构、不含数据**），挂到 `/docker-entrypoint-initdb.d`：数据目录为空时由官方 entrypoint 自动执行一次（mysql 客户端会带 `--database`，所以脚本里不需要 USE/CREATE DATABASE） |
| 15.7 | 数据持久化 | MySQL 数据挂 `./data/mysql`（宿主目录，删掉即重置）；`./data/uploads` 目录建好了但**先不挂载** —— 当前头像走 base64 存 DB、没有文件上传接口，等以后真做上传再打开 compose 里注释掉的那行 |
| 15.8 | `application.yml` 读环境变量 | 只改三处，全部是 `${ENV:默认值}` 形式，默认值 = 原来的本机配置：数据源 `url/username/password`、`jwt.secret`、`ai.service.base-url`。好处：**本地开发照旧不用配任何环境变量**，容器里由 compose 覆盖；`spring.mail.*` 本来就是 `${MAIL_USERNAME:}` / `${MAIL_PASSWORD:}` |
| 15.9 | 构建时 `-DskipTests` | `StudyKeeperApplicationTests` 是 `@SpringBootTest`，要连真 MySQL；镜像构建阶段没有库，所以 `mvn -DskipTests package`。要跑测试请在本地 `mvn test`（顺便：Dockerfile 里先单独 `dependency:go-offline` 再拷源码，改业务代码不必重下依赖） |
| 15.10 | 健康检查**不引入依赖** | 项目没有 actuator，也不为了健康检查去加。backend 只做 TCP 探活（`nc -z`，alpine 自带 busybox）；ai 用 Python 自己请求 `/health`；mysql 用 `mysqladmin ping`；nginx 用 `wget` 取首页。另外 nginx 对 backend/ai 的依赖是「启动即可」而非「健康才启动」，所以健康检查失败不会卡住整个栈（只有 backend 等 mysql 用了 `service_healthy`） |
| 15.11 | 四个服务都设 `TZ=Asia/Shanghai` | 后端算「今天」用的是 `LocalDateTime.now()`，容器默认 UTC 会差 8 小时；JDBC URL 里的 `serverTimezone=Asia/Shanghai` 保持原样 |
| 15.12 | `.env` 的边界 | `.env`（真值：MySQL 密码 / JWT 密钥 / API Key / 邮箱授权码）**不进 Git**（Study 根目录新增 .gitignore 忽略它），进 Git 的只有 `.env.example`（同 key、占位值 + 每个 key 的说明）。compose 里关键变量用 `${VAR:?人话提示}`，漏填时报清楚的错而不是起一个坏容器 |
| 15.13 | 明确不做 | HTTPS / 证书、CI/CD、监控、日志收集、K8s、镜像推仓库、多环境（dev/prod）拆分、容器内非 root 用户、MySQL 主从 / 备份 |
| 15.14 | 本次验证到哪一步 | 已验证：`docker compose config` 通过且 `config --services` 恰好 4 个（ai/mysql/backend/nginx）；`mysql/init/01-schema.sql` 在临时空库上跑通，且**与现有库逐列 + 逐索引完全一致**（80 列 / 25 个索引片段，零差异）。**未验证**：当前环境的 docker 解析不了 `registry-1.docker.io`（DNS 失败）且本地无缓存镜像，所以镜像构建与 `up` 没有实跑，需在能联网的机器上执行 `docker compose up -d --build` |




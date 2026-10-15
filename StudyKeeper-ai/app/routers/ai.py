#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AI 路由：/ai/plan 任务拆解 + /ai/chat 对话 + /ai/parse-course 课程解析
+ /ai/extract-memory 用户画像记忆提炼。

POST /ai/plan
    请求：{"goal": "...", "planDate": "2026-09-18",
           "availableSlots": [{"startTime": "19:00", "endTime": "22:00"}]}
    响应：{"tasks": [{"title": "...", "startTime": "19:00", "endTime": "19:45",
                     "priority": "high"}, ...]}

POST /ai/chat
    请求：{"message": "...",
           "history": [{"role": "user", "content": "..."}],
           "persona": "default",
           "context": {"todayTasks": [{"id": 12, "title": "...", "status": "pending",
                                       "startTime": "19:00", "endTime": "19:50"}],
                       "todayCourses": [{"courseName": "高等数学", "startTime": "08:00",
                                         "endTime": "09:40"}],
                       "todayRestTimes": [{"startTime": "12:00", "endTime": "12:30",
                                           "label": "午休"}],
                       "availableSlots": [{"startTime": "12:00", "endTime": "14:10"},
                                          {"startTime": "15:40", "endTime": "19:10"}],
                       "userMemories": [{"category": "event", "content": "用户下周要考雅思"}],
                       "isFirstMessageToday": true,
                       "yesterdaySummary": {"date": "2026-10-14", "total": 5, "completed": 3,
                                            "missed": 2,
                                            "missedTasks": [{"title": "背单词 50 个",
                                                             "startTime": "07:00",
                                                             "endTime": "07:30"}]}}}
    响应：{"reply": "...", "toolCalls": [{"name": "add_task", "arguments": {...}}]}
    工具调用只是模型的「意图」，本服务不执行它：执行、落库都在 Java 侧。
    persona 是 AI 角色，决定 system prompt 开头的「人格设定」段（见 app/services/personas.py）：
    default（默认学习助手）/ zeng_guofan（曾国藩）/ truth_doctor（真理医生）；
    不传 / null / 未知值都按 default 处理。本服务不校验取值（合法性由 Java 侧把关），
    只替换「人格设定」段，后面的通用规则与数据段不变，响应格式也不变。
    userMemories 是用户画像记忆（Java 侧查 user_memory 后传进来），用来让模型「认识」用户：
    非空时追加到 system prompt 末尾（按分类展示，并要求不要生硬罗列）；
    空数组时 system prompt 与不传这个字段完全一样。
    availableSlots 是 Java 侧算好的今日可用空档（startTime + endTime），注入 system prompt
    （一行「今天可用空档：12:00-14:10、15:40-19:10」）供「对话式排任务」参考：
    用户问「我今天该怎么安排」这类规划问题时，模型先给出 2-5 条带具体时间段的建议并问一句
    「要加吗」，用户同意后才返回 add_task（每条建议调一次）。空数组 / 不传时 prompt 里写
    「（今天没有可用空档）」，模型应回复「今天好像没有空档时间了」。
    这一行只是展示：Python 不校验空档、不复核建议是否落在空档内，也不缓存「上一版建议」
    （上一版建议靠调用方通过 history 带回来）。
    主动监督（Step 2）：context.isFirstMessageToday 说明「这条消息是不是用户今天的第一条」，
    context.yesterdaySummary 是昨天的情况（date / total / completed / missed / missedTasks，
    由 Java 侧现算，只在第一条消息时才有值）。**两个条件同时成立且昨天真排过任务（total > 0）**时，
    Python 会在 system prompt 的「今天可用空档」之后追加一段【昨天的学习情况】：
        【昨天的学习情况】（今天是新的一天，这是用户今天第一条消息）
        日期：2026-10-14
        总任务：5 个，完成 3 个，未完成 2 个
        未完成的任务：
        - 背单词 50 个（07:00-07:30）
        怎么反馈写在 chat.SUPERVISION_RULES（【主动监督规则】，与其它规则并列）：全完成就简短肯定一句、
    有未完成就先点出是哪几件（都落在同一时段时点出这个模式）+ 给一个今天可执行的小建议，
    语气跟随 persona，只说这一次、后续对话不再提。边界：不是今天第一条 / yesterdaySummary 为 null /
    昨天没任务（total = 0）时一个字都不加（不打扰用户）；Python 无状态，不记「今天提过没」——
    「每天只提一次」靠 Java 只在第一条消息时传 yesterdaySummary 保证。
    另：不在用户明确问别的问题时硬扯昨天（规则里要求先答用户的问题）。

POST /ai/chat/stream
    请求：与 /ai/chat 完全相同（message + history + context + persona）；history 同样会带给模型 ——
          流式与非流式共用同一份 messages 组装逻辑，所以「用户同意上一轮建议」这类依赖历史的
          回答在流式下也成立（主动监督的 context.isFirstMessageToday / yesterdaySummary 也一样生效）。
          请求体不合法照样由 FastAPI 返回 422。
    响应：SSE（text/event-stream），每条以 "data: " 开头、以空行结尾，JSON 用
          ensure_ascii=False（中文按原样发）。事件有三种：
              {"type": "delta", "content": "…"}                正文片段，逐段到达
              {"type": "tool_calls", "toolCalls": [...]}        攒齐的工具调用意图（可能没有）
              {"type": "end", "reply": "…"}                     本轮结束，reply 是完整回复
          出错时发一条 {"type": "error", "message": "AI 服务调用失败"}：流已经开始了，
          HTTP 状态码改不了（仍是 200），真实原因只写服务端日志。
    本接口只做一次问答，不做断线重连、不发心跳、不落库、不做多轮工具调用。

POST /ai/parse-course
    请求：{"rawText": "每周一三五 8:00-9:40 高等数学\\n..."}
    响应：{"courses": [{"courseName": "高等数学", "daysOfWeek": [1, 3, 5],
                       "startTime": "08:00", "endTime": "09:40"}],
           "failed": ["周二 晚上 英语角"]}
    解析不了的原始行放进 failed，前端可提示用户手工修正。

POST /ai/extract-memory
    请求：{"messages": [{"role": "user", "content": "我下周要考雅思"},
                        {"role": "assistant", "content": "..."}]}
    响应：{"memories": [{"category": "event", "content": "用户下周要考雅思"}]}
    category 只有 5 类：habit / emotion / event / preference / goal；
    没有值得记住的信息时 memories 为空数组。
    本接口只提炼、不落库：记忆的存储、合并、遗忘都在 Java 侧，这里不查数据库、
    不起异步任务、不做缓存。

请求体不合法（缺字段、格式不对、空 message / rawText 等）由 FastAPI 直接返回 422，
不会消耗模型调用；只有「模型调用失败」或「模型返回内容不合法」才返回 500。
"""



import json
import re
import sys
from datetime import date
from typing import Any, Literal, Optional

from fastapi import APIRouter, HTTPException, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.services import chat, course_parser, memory, planner
from app.services.llm_client import LLMError
from app.services.planner import PlanFormatError

HHMM_PATTERN = r"^([01]\d|2[0-3]):[0-5]\d$"        # 24 小时制，两位补零
SLOT_SEPARATOR = re.compile(r"\s*(?:-|~|—|至)\s*")   # 兼容 "19:00-22:00" 这种写法
MAX_SLOTS = 10
MAX_HISTORY = 50                # /ai/chat：最多带 50 条历史消息
MAX_CHAT_TASKS = 200            # /ai/chat：今日任务最多 200 条
MAX_CHAT_COURSES = 50           # /ai/chat：今日课程最多 50 条
MAX_CHAT_REST_TIMES = 50        # /ai/chat：今日休息时段最多 50 条
MAX_CHAT_MEMORIES = 50          # /ai/chat：用户画像记忆最多 50 条（Java 侧每类最多 3 条）
MAX_CHAT_SLOTS = 50             # /ai/chat：今日可用空档最多 50 条（一天排不出这么多，兜底上限）
MAX_CHAT_MISSED_TASKS = 5       # /ai/chat：昨天未完成的任务最多 5 条（Java 侧 buildYesterdaySummary 就限 5 条）
MAX_MESSAGE_LENGTH = 2000       # /ai/chat：用户这句话的长度上限
MAX_RAW_TEXT_LENGTH = 5000      # /ai/parse-course：课程文本长度上限
MAX_COURSE_NAME = 100           # /ai/parse-course：课程名长度上限
MAX_MEMORY_MESSAGES = 20        # /ai/extract-memory：最多带 20 条对话（最近 10 条左右即可）
MAX_MEMORY_CONTENT = 500        # /ai/extract-memory：单条提炼内容长度上限
WEEKDAY_MIN, WEEKDAY_MAX = 1, 7  # 1=周一 … 7=周日
AI_ERROR_DETAIL = "AI 服务调用失败"
SSE_MEDIA_TYPE = "text/event-stream"   # /ai/chat/stream 的响应类型
SSE_ERROR_EVENT = "error"              # SSE 里的出错事件（流开始后 HTTP 状态改不了，只能用事件报错）
SSE_DATA_PREFIX = "data: "             # SSE 帧前缀
SSE_FRAME_END = "\n\n"                 # SSE 帧结束


class TimeSlot(BaseModel):
    """一个可用时段，语义是左闭右开 [startTime, endTime)。"""

    startTime: str = Field(..., pattern=HHMM_PATTERN, examples=["19:00"])
    endTime: str = Field(..., pattern=HHMM_PATTERN, examples=["22:00"])

    @model_validator(mode="after")
    def check_order(self):
        # 两位补零的 "HH:mm" 可以直接按字符串比大小
        if self.endTime <= self.startTime:
            raise ValueError("endTime 必须晚于 startTime")
        return self


class PlanRequest(BaseModel):
    """/ai/plan 的请求体。"""

    model_config = ConfigDict(extra="forbid")       # 多传字段直接 422，早暴露拼写错误

    goal: str = Field(..., min_length=1, max_length=500,
                      examples=["复习高数第三章 + 背 50 个单词"])
    planDate: date = Field(..., examples=["2026-09-18"])   # 非法日期自动 422
    availableSlots: list[TimeSlot] = Field(..., min_length=1, max_length=MAX_SLOTS)

    @field_validator("goal")
    @classmethod
    def strip_goal(cls, value):
        """全是空格的 goal 不算有效输入。"""
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("goal 不能是空白")
        return cleaned

    @field_validator("availableSlots", mode="before")
    @classmethod
    def accept_string_slots(cls, value):
        """宽容一点：除了对象数组，也接受 ["19:00-22:00", "19:00~22:00"]。"""
        if not isinstance(value, list):
            return value
        normalized = []
        for item in value:
            if isinstance(item, str):
                parts = [part.strip() for part in SLOT_SEPARATOR.split(item)
                         if part.strip()]
                if len(parts) == 2:
                    normalized.append({"startTime": parts[0], "endTime": parts[1]})
                    continue
            normalized.append(item)
        return normalized


class TaskItem(BaseModel):
    """拆解出来的单条任务。"""

    title: str = Field(..., min_length=1, max_length=120)
    startTime: str = Field(..., pattern=HHMM_PATTERN)
    endTime: str = Field(..., pattern=HHMM_PATTERN)
    priority: Literal["high", "medium", "low"]


class PlanResponse(BaseModel):
    """/ai/plan 的响应体。"""

    tasks: list[TaskItem]


class ChatMessage(BaseModel):
    """/ai/chat 里的一条历史消息（由前端 / Java 传进来，本服务不持久化）。"""

    role: str = Field(..., min_length=1, max_length=20, examples=["user"])
    content: str = Field(default="", max_length=4000, examples=["今天背了 50 个单词"])


class TaskBrief(BaseModel):
    """/ai/chat 里的一条今日任务：只展示给模型看，不参与时间计算，所以时间不校验格式。"""

    id: int = Field(..., ge=0, examples=[12])
    title: str = Field(..., max_length=200, examples=["背 50 个单词"])
    status: str = Field(..., max_length=20, examples=["pending"])
    startTime: str = Field(default="", max_length=20, examples=["19:00"])
    endTime: str = Field(default="", max_length=20, examples=["19:50"])


class CourseBrief(BaseModel):
    """/ai/chat 里今天的一门课：只展示给模型看，不参与时间计算，所以时间不校验格式。"""

    courseName: str = Field(default="", max_length=200, examples=["高等数学"])
    startTime: str = Field(default="", max_length=20, examples=["08:00"])
    endTime: str = Field(default="", max_length=20, examples=["09:40"])


class RestTimeBrief(BaseModel):
    """/ai/chat 里今天的一个休息时段：同 CourseBrief，只展示给模型看，不校验时间格式。"""

    startTime: str = Field(default="", max_length=20, examples=["12:00"])
    endTime: str = Field(default="", max_length=20, examples=["12:30"])
    label: str = Field(default="", max_length=100, examples=["午休"])


class SlotBrief(BaseModel):
    """/ai/chat 里的一个可用空档（Java 侧算好的空闲时段），只展示给模型看，所以时间不校验格式。"""

    startTime: str = Field(default="", max_length=20, examples=["12:00"])
    endTime: str = Field(default="", max_length=20, examples=["14:10"])


class MemoryBrief(BaseModel):
    """/ai/chat 里一条用户画像记忆（Java 从 user_memory 查出来），只展示给模型看。

    category 不限定枚举：库里是什么就传什么，未知类别在 system prompt 里直接用英文原值。
    """

    category: str = Field(default="", max_length=50, examples=["event"])
    content: str = Field(default="", max_length=500, examples=["用户下周要考雅思"])


class MissedTaskBrief(BaseModel):
    """/ai/chat 里昨天漏掉的一条任务（Java 侧的 YesterdaySummary.missedTasks）。

    只给 title / startTime / endTime 三样，时间不校验格式（与 TaskBrief 同口径：只展示给模型看）；
    时间没有值时 Java 传空串，所以默认值也是空串。
    """

    title: str = Field(default="", max_length=200, examples=["背 50 个单词"])
    startTime: str = Field(default="", max_length=20, examples=["07:00"])
    endTime: str = Field(default="", max_length=20, examples=["07:30"])


class YesterdaySummaryBrief(BaseModel):
    """/ai/chat 里昨天的情况（主动监督用），由 Java 侧现查 task 表算出。

    只在 isFirstMessageToday = true 时才有值，其它时候是 null；昨天没任务时是 total = 0 的空对象
    （不是 null）。Python 只用它给模型排版出一段【昨天的学习情况】，不复核数字、不算完成率、不查库。
    时间字段（missedTasks 里的）没有值时是空串，与今日任务同一口径。
    """

    date: str = Field(default="", max_length=20, examples=["2026-10-14"])
    total: int = Field(default=0, ge=0, examples=[5])
    completed: int = Field(default=0, ge=0, examples=[3])
    missed: int = Field(default=0, ge=0, examples=[2])
    missedTasks: list[MissedTaskBrief] = Field(default_factory=list,
                                               max_length=MAX_CHAT_MISSED_TASKS)


class ChatContext(BaseModel):
    """/ai/chat 的上下文：今天有哪些任务、哪些课、哪些休息时段、哪些可用空档，以及用户画像记忆。

    另有主动监督用的两个字段：isFirstMessageToday（今天这条是不是用户今天的第一条消息）与
    yesterdaySummary（昨天干得怎么样，只在第一条消息时才有值）—— 都由 Java 侧现查后传进来。
    """

    todayTasks: list[TaskBrief] = Field(default_factory=list, max_length=MAX_CHAT_TASKS)
    todayCourses: list[CourseBrief] = Field(default_factory=list, max_length=MAX_CHAT_COURSES)
    todayRestTimes: list[RestTimeBrief] = Field(default_factory=list,
                                               max_length=MAX_CHAT_REST_TIMES)
    availableSlots: list[SlotBrief] = Field(default_factory=list, max_length=MAX_CHAT_SLOTS)
    userMemories: list[MemoryBrief] = Field(default_factory=list,
                                           max_length=MAX_CHAT_MEMORIES)
    # 不传 / null 都当 false（不主动提昨天），与 Java 侧「一定传 true / false」兼容
    isFirstMessageToday: Optional[bool] = Field(default=False, examples=[True])
    # 只在 isFirstMessageToday = true 时才有值；null / 不传 → system prompt 里不加【昨天的学习情况】
    yesterdaySummary: Optional[YesterdaySummaryBrief] = Field(default=None)

    @field_validator("availableSlots", mode="before")
    @classmethod
    def accept_string_slots(cls, value):
        """宽容一点：除了对象数组，也接受 ["12:00-14:10", "15:40~19:10"]（与 /ai/plan 一致）。"""
        if not isinstance(value, list):
            return value
        normalized = []
        for item in value:
            if isinstance(item, str):
                parts = [part.strip() for part in SLOT_SEPARATOR.split(item)
                         if part.strip()]
                if len(parts) == 2:
                    normalized.append({"startTime": parts[0], "endTime": parts[1]})
                    continue
            normalized.append(item)
        return normalized


class ChatRequest(BaseModel):
    """/ai/chat 的请求体；history 与 context 可以不传，也可以传空列表。

    persona 是 AI 角色（default / zeng_guofan / truth_doctor）：本服务不校验取值，
    未知值在 personas.get_persona_prompt 里回退默认人格（合法性由 Java 侧把关）。
    """

    model_config = ConfigDict(extra="forbid")       # 多传字段直接 422

    message: str = Field(..., min_length=1, max_length=MAX_MESSAGE_LENGTH,
                         examples=["帮我把背单词挪到 20:00"])
    history: list[ChatMessage] = Field(default_factory=list, max_length=MAX_HISTORY)
    context: ChatContext = Field(default_factory=ChatContext)
    persona: Optional[str] = "default"

    @field_validator("message")
    @classmethod
    def strip_message(cls, value):
        """全是空格的 message 不算有效输入。"""
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("message 不能是空白")
        return cleaned


class ToolCall(BaseModel):
    """模型表达的一次工具调用意图；本服务只返回它，不执行。"""

    name: str = Field(..., min_length=1, max_length=50, examples=["add_task"])
    arguments: dict[str, Any] = Field(default_factory=dict)


class ChatResponse(BaseModel):
    """/ai/chat 的响应体。"""

    reply: str = Field(..., examples=["好，我把背单词挪到 20:00 了。"])
    toolCalls: list[ToolCall] = Field(default_factory=list)


class CourseItem(BaseModel):
    """解析出来的一门课。daysOfWeek 用 1~7 表示周一~周日。"""

    courseName: str = Field(..., min_length=1, max_length=MAX_COURSE_NAME,
                            examples=["高等数学"])
    daysOfWeek: list[int] = Field(..., min_length=1, max_length=7, examples=[[1, 3, 5]])
    startTime: str = Field(..., pattern=HHMM_PATTERN, examples=["08:00"])
    endTime: str = Field(..., pattern=HHMM_PATTERN, examples=["09:40"])

    @field_validator("daysOfWeek")
    @classmethod
    def check_days(cls, value):
        """星期只能是 1~7。"""
        bad = [day for day in value if not (WEEKDAY_MIN <= day <= WEEKDAY_MAX)]
        if bad:
            raise ValueError("daysOfWeek 只能是 1-7，收到 {}".format(bad))
        return sorted(set(value))


class ParseCourseRequest(BaseModel):
    """/ai/parse-course 的请求体：一段自由格式的课程文本。"""

    model_config = ConfigDict(extra="forbid")       # 多传字段直接 422

    rawText: str = Field(..., min_length=1, max_length=MAX_RAW_TEXT_LENGTH,
                         examples=["每周一三五 8:00-9:40 高等数学\n每周二 14:00-15:40 大学英语"])

    @field_validator("rawText")
    @classmethod
    def strip_raw_text(cls, value):
        """全是空白/换行的文本不算有效输入。"""
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("rawText 不能是空白")
        return cleaned


class ParseCourseResponse(BaseModel):
    """/ai/parse-course 的响应体。"""

    courses: list[CourseItem] = Field(default_factory=list)
    failed: list[str] = Field(default_factory=list)


class MemoryMessage(BaseModel):
    """/ai/extract-memory 里的一条对话消息（由前端 / Java 传进来，本服务不持久化）。"""

    role: str = Field(..., min_length=1, max_length=20, examples=["user"])
    content: str = Field(default="", max_length=4000, examples=["我下周要考雅思"])


class ExtractMemoryRequest(BaseModel):
    """/ai/extract-memory 的请求体：最近几轮对话（10 条左右即可，最多 20 条）。"""

    model_config = ConfigDict(extra="forbid")       # 多传字段直接 422

    messages: list[MemoryMessage] = Field(..., min_length=1, max_length=MAX_MEMORY_MESSAGES)

    @model_validator(mode="after")
    def check_has_content(self):
        """整段对话全是空白的消息不算有效输入。"""
        if not any(item.content.strip() for item in self.messages):
            raise ValueError("messages 里至少要有一条非空内容")
        return self


class ExtractedMemory(BaseModel):
    """一条值得长期记住的用户信息；category 只能是约定的 5 类。"""

    category: Literal["habit", "emotion", "event", "preference", "goal"] = Field(
        ..., examples=["event"])
    content: str = Field(..., min_length=1, max_length=MAX_MEMORY_CONTENT,
                         examples=["用户下周要考雅思"])


class ExtractMemoryResponse(BaseModel):
    """/ai/extract-memory 的响应体；没有值得记住的信息时 memories 为空数组。"""

    memories: list[ExtractedMemory] = Field(default_factory=list)


router = APIRouter(prefix="/ai", tags=["ai"])


@router.post("/plan", response_model=PlanResponse, summary="AI 任务拆解",
             responses={status.HTTP_500_INTERNAL_SERVER_ERROR:
                        {"description": AI_ERROR_DETAIL}})
def ai_plan(payload: PlanRequest):
    """把目标拆成落在可用时段内的任务；模型侧或格式侧出错统一返回 500。"""
    try:
        tasks = planner.plan(
            goal=payload.goal,
            plan_date=payload.planDate.isoformat(),
            available_slots=[slot.model_dump() for slot in payload.availableSlots],
        )
    except (LLMError, PlanFormatError) as exc:
        # 真实原因只写服务端日志，响应里不外泄
        print("[错误] /ai/plan 失败：{}".format(exc), file=sys.stderr)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=AI_ERROR_DETAIL) from exc
    except Exception as exc:                      # 兜底，别把 traceback 甩给调用方
        print("[错误] /ai/plan 未预期异常：{}".format(exc), file=sys.stderr)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=AI_ERROR_DETAIL) from exc

    return PlanResponse(tasks=tasks)


@router.post("/chat", response_model=ChatResponse,
             summary="AI 对话（含工具调用意图）",
             responses={status.HTTP_500_INTERNAL_SERVER_ERROR:
                        {"description": AI_ERROR_DETAIL}})
def ai_chat(payload: ChatRequest):
    """闲聊就给一句回复；用户想增删改任务就返回工具调用意图，由 Java 侧执行。"""
    try:
        result = chat.chat(
            message=payload.message,
            history=[item.model_dump() for item in payload.history],
            context={
                "todayTasks": [task.model_dump() for task in payload.context.todayTasks],
                "todayCourses": [course.model_dump()
                                 for course in payload.context.todayCourses],
                "todayRestTimes": [rest_time.model_dump()
                                   for rest_time in payload.context.todayRestTimes],
                "availableSlots": [slot.model_dump() for slot in payload.context.availableSlots],
                "userMemories": [memory.model_dump()
                                 for memory in payload.context.userMemories],
                # 主动监督：isFirstMessageToday 每次照传；yesterdaySummary 没有值时给 null
                # （不是今天第一条 / 昨天没任务时 Java 传来的就是 null 或 total = 0 的空对象）
                "isFirstMessageToday": payload.context.isFirstMessageToday,
                "yesterdaySummary": (payload.context.yesterdaySummary.model_dump()
                                     if payload.context.yesterdaySummary else None),
            },
            persona_id=payload.persona,
        )
    except LLMError as exc:
        # 真实原因只写服务端日志，响应里不外泄
        print("[错误] /ai/chat 失败：{}".format(exc), file=sys.stderr)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=AI_ERROR_DETAIL) from exc
    except Exception as exc:                      # 兜底，别把 traceback 甩给调用方
        print("[错误] /ai/chat 未预期异常：{}".format(exc), file=sys.stderr)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=AI_ERROR_DETAIL) from exc

    return ChatResponse(**result)


def sse_event(payload):
    """把一条事件对象包成 SSE 帧：{"type": "delta", ...} → 'data: {...}\\n\\n'。

    ensure_ascii=False：中文原样发出去（不转成 \\uXXXX），前端拿到就能直接显示。
    """
    return "{}{}{}".format(SSE_DATA_PREFIX,
                           json.dumps(payload, ensure_ascii=False),
                           SSE_FRAME_END)


@router.post("/chat/stream", response_class=StreamingResponse,
             summary="AI 对话（SSE 流式）")
def ai_chat_stream(payload: ChatRequest):
    """流式对话：正文一段段推给客户端；工具调用意图攒齐后用一条事件给出。

    请求体与 /ai/chat 完全相同：history 与 context 都照传，history 同样会带给模型
    （流式与非流式共用同一份 messages 组装，不再有「流式忽略 history」这回事）。
    响应是 SSE，事件种类见模块头部注释；出错时发一条 error 事件（流已经开始写了，
    HTTP 状态码改不了），真实原因只写服务端日志。每次请求都是一条新流，不缓存、不落库。
    """
    # 上下文与历史都在请求作用域里先组装好，流里面只做「读模型 → 转 SSE」这一件事
    context = {
        "todayTasks": [task.model_dump() for task in payload.context.todayTasks],
        "todayCourses": [course.model_dump() for course in payload.context.todayCourses],
        "todayRestTimes": [rest_time.model_dump()
                           for rest_time in payload.context.todayRestTimes],
        "availableSlots": [slot.model_dump() for slot in payload.context.availableSlots],
        "userMemories": [memory.model_dump() for memory in payload.context.userMemories],
        # 主动监督：与 /ai/chat 完全一致（两条链路共用 build_messages，注入内容天然一致）
        "isFirstMessageToday": payload.context.isFirstMessageToday,
        "yesterdaySummary": (payload.context.yesterdaySummary.model_dump()
                             if payload.context.yesterdaySummary else None),
    }
    history = [item.model_dump() for item in payload.history]

    def event_stream():
        try:
            for event in chat.chat_stream(payload.message, history, context, payload.persona):
                yield sse_event(event)
        except LLMError as exc:
            # 真实原因只写服务端日志，响应里不外泄
            print("[错误] /ai/chat/stream 失败：{}".format(exc), file=sys.stderr)
            yield sse_event({"type": SSE_ERROR_EVENT, "message": AI_ERROR_DETAIL})
        except Exception as exc:                  # 兜底，别把 traceback 甩给调用方
            print("[错误] /ai/chat/stream 未预期异常：{}".format(exc), file=sys.stderr)
            yield sse_event({"type": SSE_ERROR_EVENT, "message": AI_ERROR_DETAIL})

    return StreamingResponse(event_stream(), media_type=SSE_MEDIA_TYPE)


@router.post("/parse-course", response_model=ParseCourseResponse,
             summary="课程文本解析",
             responses={status.HTTP_500_INTERNAL_SERVER_ERROR:
                        {"description": AI_ERROR_DETAIL}})
def ai_parse_course(payload: ParseCourseRequest):
    """把自由格式的课程文本转成结构化课程列表；解析不了的原始行放进 failed。"""
    try:
        result = course_parser.parse_courses(payload.rawText)
    except (LLMError, PlanFormatError) as exc:
        # 真实原因只写服务端日志，响应里不外泄
        print("[错误] /ai/parse-course 失败：{}".format(exc), file=sys.stderr)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=AI_ERROR_DETAIL) from exc
    except Exception as exc:                      # 兜底，别把 traceback 甩给调用方
        print("[错误] /ai/parse-course 未预期异常：{}".format(exc), file=sys.stderr)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=AI_ERROR_DETAIL) from exc

    return ParseCourseResponse(**result)


@router.post("/extract-memory", response_model=ExtractMemoryResponse,
             summary="用户画像记忆提炼",
             responses={status.HTTP_500_INTERNAL_SERVER_ERROR:
                        {"description": AI_ERROR_DETAIL}})
def ai_extract_memory(payload: ExtractMemoryRequest):
    """从最近几轮对话里提炼值得长期记住的用户信息；只提炼，不落库、不合并。"""
    try:
        memories = memory.extract([item.model_dump() for item in payload.messages])
    except (LLMError, PlanFormatError) as exc:
        # 真实原因只写服务端日志，响应里不外泄
        print("[错误] /ai/extract-memory 失败：{}".format(exc), file=sys.stderr)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=AI_ERROR_DETAIL) from exc
    except Exception as exc:                      # 兜底，别把 traceback 甩给调用方
        print("[错误] /ai/extract-memory 未预期异常：{}".format(exc), file=sys.stderr)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=AI_ERROR_DETAIL) from exc

    return ExtractMemoryResponse(memories=memories)

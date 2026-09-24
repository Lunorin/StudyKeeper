#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用户画像记忆提炼：把最近几轮对话交给大模型，提炼出「值得长期记住」的用户信息。

对外只有一个入口：
    extract(messages) -> [{"category": "event", "content": "用户下周要考雅思"}, ...]

messages 由调用方（前端 / Java）现传，形如：
    [{"role": "user", "content": "我下周要考雅思"},
     {"role": "assistant", "content": "那就先紧着口语练"},
     ...]                                  最近 10 条左右即可

category 只有 5 类：
    habit       学习习惯（如「用户偏好上午学数学」）
    emotion     情绪状态（如「用户对英语有畏难情绪」）
    event       重要事件（如「用户下周要考雅思」）
    preference  偏好（如「用户不喜欢超过 1 小时的任务」）
    goal        长期目标（如「用户想 3 个月刷完力扣 hot100」）

校验规则（模型不听话也不会把脏数据交出去）：
- category 必须落在 5 类里，其它的一律丢弃；
- content 收成一行（合并空白、去首尾），空白丢弃，超长按 MAX_CONTENT_LENGTH 截断
  （提示词里已要求不超过 50 字，这个上限只是兜底）；
- 一次最多返回 MAX_MEMORIES 条，防止模型一口气吐一堆；
- 一条都没提炼出来是正常结果（返回空列表，只往 stderr 打警告日志），
  但 JSON 本身不合法 / 缺 memories 数组时抛 MemoryFormatError，由路由层映射成 500。

边界（重要）：本模块只做「一次提炼」——不查库、不落库、不调 Java、不起异步任务、
不做缓存，也不做记忆合并 / 遗忘机制、向量检索 / 语义相似度，这些都在后续步骤里
由 Java 侧或别的模块负责。
"""

import json
import sys

from app.services import llm_client
from app.services.planner import PlanFormatError, extract_json

CATEGORIES = ("habit", "emotion", "event", "preference", "goal")
MAX_MEMORIES = 5                 # 一次最多返回 5 条，防止模型输出一大堆
MAX_CONTENT_LENGTH = 500         # 单条 content 的兜底上限（提示词要求 50 字内），超长截断
MAX_MESSAGES = 20                # 最多把最近 20 条消息拼进提示词，避免上下文撑爆

ROLE_LABELS = {"user": "用户", "assistant": "助手", "system": "系统"}
UNKNOWN_ROLE = "未知"
SEPARATOR = "："                 # 角色与内容之间的分隔符

JSON_SHAPE = '{"memories": [{"category": "event", "content": "用户下周要考雅思"}]}'

SYSTEM_PROMPT = (
    "你是一个用户信息提炼助手。\n"
    "从一段学习助手与用户的对话中，提炼出「值得长期记住」的用户信息。\n"
    "只输出 json，不要解释。\n"
    "\n"
    "值得记住的信息包括 5 类：\n"
    "- habit：学习习惯（如\"用户偏好上午学数学\"）\n"
    "- emotion：情绪状态（如\"用户对英语有畏难情绪\"）\n"
    "- event：重要事件（如\"用户下周要考雅思\"）\n"
    "- preference：偏好（如\"用户不喜欢超过 1 小时的任务\"）\n"
    "- goal：长期目标（如\"用户想 3 个月刷完力扣 hot100\"）\n"
    "\n"
    "判断标准：\n"
    "- 只提炼用户主动提到的、可能持续影响后续对话的信息\n"
    "- 不要提炼一次性的、临时的信息（如\"用户今天想吃面\"）\n"
    "- 不要提炼 AI 说的内容\n"
    "- 不要提炼用户的提问本身\n"
    "\n"
    "输出格式：\n"
    + JSON_SHAPE + "\n"
    "\n"
    "如果没有值得记住的信息，返回：\n"
    '{"memories": []}\n'
    "\n"
    "每条 content 不超过 50 字，用第三人称描述。"
)


class MemoryFormatError(PlanFormatError):
    """记忆提炼结果不符合约定格式（PlanFormatError 的子类，路由层统一映射成 500）。"""


def format_role(role):
    """把消息角色翻成给模型看的标签；未知角色原样保留。"""
    text = str(role or "").strip()
    if not text:
        return UNKNOWN_ROLE
    return ROLE_LABELS.get(text.lower(), text)


def normalize_content(text):
    """把 content 收成一行：合并空白、去首尾、超长截断；空内容或非字符串返回 None。"""
    if not isinstance(text, str):
        return None

    cleaned = " ".join(text.split())
    if not cleaned:
        return None
    if len(cleaned) > MAX_CONTENT_LENGTH:
        cleaned = cleaned[:MAX_CONTENT_LENGTH].rstrip() + "…"
    return cleaned


def normalize_category(value):
    """category 归一成小写并校验；不属于 5 类返回 None。"""
    if not isinstance(value, str):
        return None

    cleaned = value.strip().lower()
    if cleaned not in CATEGORIES:
        return None
    return cleaned


def normalize_memory(item):
    """把模型返回的一条记忆归一化；不合法（类别不在 5 类 / 内容为空）返回 None。

    返回的字典固定只有两个字段：模型多给的字段一律丢掉，不会透给调用方。
    """
    if not isinstance(item, dict):
        return None

    category = normalize_category(item.get("category"))
    content = normalize_content(item.get("content"))
    if category is None or content is None:
        return None
    return {"category": category, "content": content}


def describe_item(item):
    """把模型返回的一条原始记忆转成一行日志文本。"""
    if isinstance(item, (dict, list)):
        return json.dumps(item, ensure_ascii=False)
    return str(item)


def format_messages(messages):
    """把最近几轮对话排成给模型看的多行文本；一条可用内容都没有时返回空串。

    取的是最后 MAX_MESSAGES 条（对话越靠后越能反映用户当下的状态），
    内容为空的行直接跳过。
    """
    lines = []
    for item in messages[-MAX_MESSAGES:]:
        if not isinstance(item, dict):
            continue
        content = item.get("content")
        if not isinstance(content, str) or not content.strip():
            continue
        lines.append("{}{}{}".format(format_role(item.get("role")),
                                     SEPARATOR,
                                     " ".join(content.split())))
    return "\n".join(lines)


def build_messages(conversation):
    """拼请求消息：system 说明提炼规则与输出格式，user 给出待提炼的对话文本。"""
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "请从以下对话中提炼：\n" + conversation},
    ]


def parse_memories(raw_text):
    """解析模型返回的文本，输出校验过的 memories 列表；格式不对抛 MemoryFormatError。"""
    try:
        data = extract_json(raw_text)          # 非法 JSON / 空内容都在这里拦下
    except PlanFormatError as exc:
        # 统一成自己的格式异常，调用方只需处理 MemoryFormatError 一种
        raise MemoryFormatError(str(exc)) from exc

    if not isinstance(data, dict):
        raise MemoryFormatError("JSON 顶层必须是对象，收到 {}".format(type(data).__name__))

    raw_memories = data.get("memories")
    if not isinstance(raw_memories, list):
        raise MemoryFormatError("JSON 里缺少 memories 数组（约定格式：{}）".format(JSON_SHAPE))

    memories = []
    for item in raw_memories:
        entry = normalize_memory(item)
        if entry is None:
            # 被丢弃的都打出来，方便定位模型到底给了什么
            print("[警告] 丢弃不可用的记忆：{}".format(describe_item(item)), file=sys.stderr)
            continue
        memories.append(entry)
        if len(memories) >= MAX_MEMORIES:
            break
    return memories


def extract(messages):
    """对外入口：校验输入 → 调模型 → 解析校验 → 返回 memories 列表。

    失败时抛 LLMError（模型侧）或 MemoryFormatError（格式侧），由路由层统一处理；
    模型认为「没有值得记住的信息」时返回空列表。
    """
    if not isinstance(messages, list) or not messages:
        # 路由层已用 Pydantic 拦过，这里再防一层
        raise MemoryFormatError("messages 不能为空")

    conversation = format_messages(messages)
    if not conversation:
        raise MemoryFormatError("messages 里没有可提炼的对话内容")

    raw_text = llm_client.chat_json(build_messages(conversation))
    return parse_memories(raw_text)

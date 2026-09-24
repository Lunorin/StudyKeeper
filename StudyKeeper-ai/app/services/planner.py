#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AI 任务拆解：把「今日目标 + 可用时段」交给大模型，拆成带时间段的任务列表。

对外只有一个入口：
    plan(goal, plan_date, available_slots) -> [task, ...]

tasks 的结构（同时也是 POST /ai/plan 的响应结构）：
    [
        {"title": "背 50 个单词", "startTime": "19:00", "endTime": "19:45", "priority": "high"},
        {"title": "复习高数第三章", "startTime": "20:00", "endTime": "21:00", "priority": "medium"}
    ]

校验规则（模型不听话也不会把脏数据交给调用方）：
- title 收成一行、超长截断；标题为空的条目丢弃；
- startTime / endTime 统一成 HH:mm，endTime 必须晚于 startTime；
- 任务必须完整落在某个可用时段的 [slotStart, slotEnd) 内（时间先换算成「当天第几分钟」
  再比较），越界的一律丢弃，并往 stderr 打一行警告日志，方便定位模型到底给了什么；
- priority 只能是 high / medium / low，其它值兜底成 medium；
- 与已保留任务时间重叠的丢弃，最后按 startTime 升序；
- 一条都不剩就抛 PlanFormatError：宁可报错，也不返回空计划；
- system prompt 末尾带上「当前时间」（app/utils/time_context.py，每次请求现取）：
  planDate 只说明要排哪一天，模型自己不知道「今天」是几号几点。

本模块不 import FastAPI / Pydantic，也不 import 根目录那个命令行版 planner.py
（根目录模块不是包，导入会污染路径），只借用了「正则归一化时间、容忍代码块包裹」
这类思路，提示词与数据结构都是重新设计的。
"""

import json
import re
import sys

from app.services import llm_client
from app.utils.time_context import now_text

JSON_SHAPE = ('{"tasks": [{"title": "背 50 个单词", "startTime": "19:00", '
              '"endTime": "19:45", "priority": "high"}]}')

SYSTEM_PROMPT = (
    "你是一个学习规划助手，负责把用户的目标拆解成当天可执行、带明确时间段的任务。\n"
    "只输出一个 json 对象，不要输出解释、前后缀，也不要用 Markdown 代码块（不要 ```）。\n"
    "输出格式（json）：" + JSON_SHAPE + "\n"
    "生成要求：\n"
    "1. 每个任务必须包含 4 个字段：title / startTime / endTime / priority，一个都不能少；\n"
    "2. startTime、endTime 用 24 小时制 HH:mm（小时补零成两位），endTime 必须晚于 startTime；\n"
    "3. 所有任务都必须完整落在用户给出的「可用时段」之内，不能超出时段边界，任务之间不要重叠；\n"
    "4. 单个任务时长 30~90 分钟，粒度适中：一条任务只做一件事，写清做什么、做到什么程度；\n"
    "5. priority 只能是 high / medium / low 三者之一，按重要紧急程度给；\n"
    "6. tasks 按时间先后排列；任务数量按可用时长决定；\n"
    "7. 不要安排吃饭、睡觉、休息这类日常事务，也不要安排与目标无关的内容；\n"
    "8. 目标太大就拆成几个阶段任务，不要出现「待定」「自行安排」这类空话。"
)

HOUR_PATTERN = re.compile(r"(\d{1,2})\s*[:：]\s*(\d{2})")
FENCE = "```"
MAX_TITLE_LENGTH = 120                       # 单条标题上限，超长截断
PRIORITIES = ("high", "medium", "low")
DEFAULT_PRIORITY = "medium"

# 任务被丢弃的原因（check_task 返回，parse_tasks 据此打日志）
REASON_UNPARSABLE = "标题为空或时间格式非法"
REASON_TIME_ORDER = "endTime 不晚于 startTime"
REASON_OUT_OF_SLOTS = "超出可用时段"


class PlanFormatError(ValueError):
    """模型返回的内容不符合约定格式；message 可直接写进日志。"""


def parse_hour(text):
    """把 "9:00" / "09:00" / "09：00" 规整成 "HH:mm"；解析不出来返回 None。"""
    if text is None or isinstance(text, bool):
        return None
    match = HOUR_PATTERN.search(str(text))
    if match is None:
        return None
    hour, minute = int(match.group(1)), int(match.group(2))
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    return "%02d:%02d" % (hour, minute)


def to_minutes(hour_text):
    """把 "HH:mm" 换算成当天的第几分钟，用来比较先后和时长。"""
    hour, minute = str(hour_text).split(":")
    return int(hour) * 60 + int(minute)


def normalize_title(text):
    """把标题收成一行：合并空白、去首尾、超长截断；空内容返回 None。"""
    if text is None:
        return None
    if not isinstance(text, str):
        text = str(text)
    cleaned = " ".join(text.split())
    if not cleaned:
        return None
    if len(cleaned) > MAX_TITLE_LENGTH:
        cleaned = cleaned[:MAX_TITLE_LENGTH].rstrip() + "…"
    return cleaned


def normalize_slots(available_slots):
    """把可用时段统一成 [{"startTime": "HH:mm", "endTime": "HH:mm"}, ...]。

    接受两种写法（routers 已用 Pydantic 校验过，这里再防一层）：
        {"startTime": "19:00", "endTime": "22:00"}   或   ("19:00", "22:00")
    非法条目直接忽略；一个可用时段都没有就抛 PlanFormatError。
    归一化结果与输入同形，所以可以重复调用。
    """
    slots = []
    for item in available_slots or []:
        if isinstance(item, dict):
            start = parse_hour(item.get("startTime"))
            end = parse_hour(item.get("endTime"))
        elif isinstance(item, (list, tuple)) and len(item) == 2:
            start = parse_hour(item[0])
            end = parse_hour(item[1])
        else:
            continue
        if start is None or end is None:
            continue
        if to_minutes(end) <= to_minutes(start):
            continue
        slots.append({"startTime": start, "endTime": end})

    if not slots:
        raise PlanFormatError("availableSlots 里没有一个可用的时段")
    return slots


def build_messages(goal, plan_date, available_slots):
    """拼请求消息：system 说明输出格式（末尾带当前时间），user 给出日期 / 可用时段 / 目标。

    当前时间每次调用都现取，不做缓存：服务可能连续跑很多天。
    """
    slots = normalize_slots(available_slots)
    slot_text = "；".join(
        "{} 到 {}".format(slot["startTime"], slot["endTime"]) for slot in slots)

    user_content = (
        "系统信息：计划日期是 {date}。\n"
        "可用时段（任务只能安排在这些区间内）：{slots}\n"
        "我的目标：{goal}\n"
        "请按约定的 json 格式输出今天的任务拆解。"
    ).format(date=plan_date, slots=slot_text, goal=goal)

    system_content = "{}\n\n当前时间：{}".format(SYSTEM_PROMPT, now_text())

    return [
        {"role": "system", "content": system_content},
        {"role": "user", "content": user_content},
    ]


def extract_json(raw_text):
    """从模型回复里抠出 JSON 对象；容忍 ```json 包裹和前后多余的话。

    解析不出来时抛 PlanFormatError。
    """
    if not isinstance(raw_text, str) or not raw_text.strip():
        raise PlanFormatError("模型没有返回任何内容")

    text = raw_text.strip()

    # 去掉 ```json ... ``` / ``` ... ``` 包裹
    if text.startswith(FENCE):
        text = text[len(FENCE):].lstrip()
        if text[:4].lower().startswith("json"):
            text = text[4:]
        tail = text.rfind(FENCE)
        if tail != -1:
            text = text[:tail]
        text = text.strip()

    # 先整段直解，失败再取第一个 { 到最后一个 } 之间的内容重试
    candidates = [text]
    start, end = text.find("{"), text.rfind("}")
    if start != -1 and end > start:
        candidates.append(text[start:end + 1])

    for candidate in candidates:
        try:
            return json.loads(candidate)
        except ValueError:
            continue
    raise PlanFormatError("模型返回的内容不是合法 JSON")


def within_slots(start_minutes, end_minutes, slots):
    """任务 [start, end) 是否完整落在某一个可用时段内。"""
    for slot in slots:
        if (start_minutes >= to_minutes(slot["startTime"])
                and end_minutes <= to_minutes(slot["endTime"])):
            return True
    return False


def normalize_task(item, slots):
    """单条任务归一化；不合格返回 None（丢弃原因见 check_task）。"""
    return check_task(item, slots)[0]


def check_task(item, slots):
    """校验 + 归一化一条任务，返回 (任务, None) 或 (None, 丢弃原因)。

    硬约束（模型不听话也不会把越界任务放出去）：
    把 startTime / endTime 换算成「当天第几分钟」，检查 [start, end) 是否完整落在
    某个可用时段的 [slotStart, slotEnd) 内；越界的一律丢弃。
    """
    if not isinstance(item, dict):
        return None, REASON_UNPARSABLE

    title = normalize_title(item.get("title"))
    start = parse_hour(item.get("startTime"))
    end = parse_hour(item.get("endTime"))
    if title is None or start is None or end is None:
        return None, REASON_UNPARSABLE

    start_minutes, end_minutes = to_minutes(start), to_minutes(end)
    if end_minutes <= start_minutes:
        return None, REASON_TIME_ORDER
    if not within_slots(start_minutes, end_minutes, slots):
        return None, REASON_OUT_OF_SLOTS

    priority = str(item.get("priority") or "").strip().lower()
    if priority not in PRIORITIES:
        priority = DEFAULT_PRIORITY

    return {"title": title, "startTime": start, "endTime": end, "priority": priority}, None


def describe_item(item):
    """把模型返回的一条原始任务转成一行日志文本。"""
    if isinstance(item, (dict, list)):
        return json.dumps(item, ensure_ascii=False)
    return str(item)


def describe_slots(slots):
    """"09:00 - 12:00；19:00 - 22:00" 形式，用于日志和错误信息。"""
    return "；".join("{} - {}".format(slot["startTime"], slot["endTime"]) for slot in slots)


def drop_overlaps(tasks):
    """丢掉与已保留任务时间重叠的后一条，保证这份计划不会自己打架。"""
    kept = []
    for task in tasks:
        if kept and to_minutes(task["startTime"]) < to_minutes(kept[-1]["endTime"]):
            continue
        kept.append(task)
    return kept


def parse_tasks(raw_text, available_slots):
    """解析模型返回的文本，输出校验过的 tasks 列表；格式不对抛 PlanFormatError。"""
    slots = normalize_slots(available_slots)

    data = extract_json(raw_text)
    if not isinstance(data, dict):
        raise PlanFormatError("JSON 顶层必须是对象，收到 %s" % type(data).__name__)

    raw_tasks = data.get("tasks")
    if not isinstance(raw_tasks, list):
        raise PlanFormatError("JSON 里缺少 tasks 数组（约定格式：%s）" % JSON_SHAPE)

    tasks = []
    for item in raw_tasks:
        task, reason = check_task(item, slots)
        if task is not None:
            tasks.append(task)
            continue

        # 被丢弃的都打出来，方便定位模型到底给了什么
        if reason == REASON_OUT_OF_SLOTS:
            print("[警告] 丢弃超出可用时段的任务：{}".format(describe_item(item)),
                  file=sys.stderr)
        else:
            print("[警告] 丢弃不可用的任务（{}）：{}".format(reason, describe_item(item)),
                  file=sys.stderr)

    if not tasks:
        raise PlanFormatError("tasks 里没有一条可用任务（标题为空 / 时间非法 / 超出可用时段）；"
                              "可用时段：{}".format(describe_slots(slots)))

    tasks.sort(key=lambda task: to_minutes(task["startTime"]))
    return drop_overlaps(tasks)


def plan(goal, plan_date, available_slots):
    """对外入口：校验输入 → 调模型 → 解析校验 → 返回 tasks 列表。

    失败时抛 LLMError（模型侧）或 PlanFormatError（格式侧），由路由层统一处理。
    """
    slots = normalize_slots(available_slots)      # 先自查输入，避免白花一次模型调用
    messages = build_messages(goal, plan_date, slots)
    raw_text = llm_client.chat_json(messages)
    return parse_tasks(raw_text, slots)

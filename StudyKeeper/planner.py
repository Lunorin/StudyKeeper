#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""今日任务规划：把「今天要做什么」交给大模型拆成每小时计划，再校验 / 渲染出来。

分工（纯标准库，不导入 main，可以单独测试）：
- build_messages() : 拼出「只返回 JSON」的请求消息，把日期 / 星期 / 现在几点喂给模型；
- parse_plan()     : 解析并校验模型返回的 JSON，得到统一结构的计划（格式不对就抛错）；
- render_plan()    : 把计划排成给人看的几行文本。

计划结构（也是写进 data/plans.json 的结构）：
    {
        "date":       "2026-09-17",              # 这份计划是哪一天的
        "created_at": "2026-09-17 09:05:12",     # 生成时间（由 main.py 补上）
        "source":     "背单词、写完高数作业",      # 你当时说的原话（由 main.py 补上）
        "tasks": [
            {"hour": "09:00", "task": "背 50 个单词"},
            {"hour": "10:00", "task": "写完高数作业第 3 章"}
        ]
    }
"""

import json
import re

# 模型必须输出的 JSON 形状：写进系统提示词，也写在上面文档里
JSON_SHAPE = '{"date": "YYYY-MM-DD", "tasks": [{"hour": "HH:MM", "task": "..."}]}'

SYSTEM_PROMPT = (
    "你是一个学习计划助理，负责把用户说的目标拆成一份「按小时安排」的今日计划。\n"
    "只输出一个 json 对象，不要输出解释、前后缀，也不要用 Markdown 代码块（不要 ```）。\n"
    "json 格式固定为：" + JSON_SHAPE + "\n"
    "生成规则：\n"
    "1. date 必须是系统给出的今天日期；\n"
    "2. hour 用 24 小时制的 HH:MM（小时补零成两位），从系统给出的当前时间之后开始排，"
    "逐条递增、不重复；\n"
    "3. 每条 task 一句话、具体可执行（写清做什么、做到什么程度），"
    "不要出现「待定」「自行安排」这类空话；\n"
    "4. 任务 3~8 条，最晚排到当天 23:00；目标太大就拆成几个阶段；\n"
    "5. 只排学习任务，不要排吃饭、睡觉、休息之类的日常事务。"
)

HOUR_PATTERN = re.compile(r"(\d{1,2})\s*[:：]\s*(\d{2})")
FENCE = "```"
MAX_TASK_LENGTH = 120   # 单条任务文字上限，超长就截断，避免把屏幕刷乱


class PlanFormatError(ValueError):
    """模型返回的计划不符合约定格式；message 是可直接打印的中文说明。"""


def parse_hour(text):
    """把 "9:00" / "09:00" / "09：00" 规整成 "09:00"；解析不出来返回 None。"""
    if text is None:
        return None
    match = HOUR_PATTERN.search(str(text))
    if match is None:
        return None
    hour, minute = int(match.group(1)), int(match.group(2))
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    return "%02d:%02d" % (hour, minute)


def hour_minutes(hour_text):
    """把 "HH:MM" 换算成当天的第几分钟，用来排序。"""
    hour, minute = str(hour_text).split(":")
    return int(hour) * 60 + int(minute)


def normalize_task(text):
    """把任务文字收成一行：合并空白、去掉首尾、超长截断；空内容返回 None。"""
    if text is None:
        return None
    if not isinstance(text, str):
        text = str(text)
    cleaned = " ".join(text.split())
    if not cleaned:
        return None
    if len(cleaned) > MAX_TASK_LENGTH:
        cleaned = cleaned[:MAX_TASK_LENGTH].rstrip() + "…"
    return cleaned


def extract_json(raw_text):
    """从模型回复里抠出 JSON 对象；容忍 Markdown 代码块和前后多余的话。

    解析不出来时抛 PlanFormatError。
    """
    if not isinstance(raw_text, str) or not raw_text.strip():
        raise PlanFormatError("模型没有返回任何内容")

    cleaned = raw_text.strip()

    # 1) 去掉 ```json ... ``` / ``` ... ``` 包裹
    if cleaned.startswith(FENCE):
        cleaned = cleaned[len(FENCE):]
        if cleaned[:4].lower().startswith("json"):
            cleaned = cleaned[4:]
        end = cleaned.rfind(FENCE)
        if end != -1:
            cleaned = cleaned[:end]
        cleaned = cleaned.strip()

    # 2) 正常情况：整段就是 JSON
    try:
        return json.loads(cleaned)
    except ValueError:
        pass

    # 3) 兜底：取第一个 { 到最后一个 } 之间的内容再试一次
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start == -1 or end <= start:
        raise PlanFormatError("返回内容里找不到 JSON 对象")
    try:
        return json.loads(cleaned[start:end + 1])
    except ValueError as exc:
        raise PlanFormatError("JSON 解析失败：%s" % exc)


def parse_plan(raw_text, date_str):
    """把模型返回的文本解析成计划字典；格式不对时抛 PlanFormatError。

    - date 一律以调用方给的今天为准（模型返回的 date 只作参考，不采用）；
    - tasks 里 hour 统一成 HH:MM、task 收成一行，缺字段或时间非法的条目直接丢掉；
    - 剩下一条可用任务都没有就报错，避免往计划文件里写空计划。
    """
    data = extract_json(raw_text)
    if not isinstance(data, dict):
        raise PlanFormatError("JSON 顶层必须是对象，收到 %s" % type(data).__name__)

    raw_tasks = data.get("tasks")
    if not isinstance(raw_tasks, list):
        raise PlanFormatError("JSON 里缺少 tasks 数组（约定格式：%s）" % JSON_SHAPE)

    tasks = []
    for item in raw_tasks:
        if not isinstance(item, dict):
            continue
        hour = parse_hour(item.get("hour"))
        task = normalize_task(item.get("task"))
        if hour is None or task is None:
            continue
        tasks.append({"hour": hour, "task": task})

    if not tasks:
        raise PlanFormatError("tasks 里没有一条可用任务（hour 要 HH:MM，task 不能为空）")

    tasks.sort(key=lambda item: hour_minutes(item["hour"]))
    return {"date": date_str, "tasks": tasks}


def render_plan(plan, weekday=None):
    """把计划排成可直接逐行打印的文本。"""
    tasks = plan.get("tasks") or []
    suffix = ("（%s）" % weekday) if weekday else ""
    lines = ["[今日计划] %s%s：共 %d 项" % (plan.get("date", ""), suffix, len(tasks))]
    if not tasks:
        lines.append("  （这份计划里没有任何任务）")
        return lines

    width = max(len(item["hour"]) for item in tasks)
    for item in tasks:
        lines.append("  %s  %s" % (item["hour"].ljust(width), item["task"]))

    source = plan.get("source")
    if source:
        lines.append("  依据你说的话：%s" % source)
    return lines


def build_messages(answer, moment, weekday, period=None):
    """拼规划请求：system 说明输出格式，user 给出「今天几号 + 现在几点 + 我的目标」。

    answer  : 用户回答的原话，如「背单词、写完高数作业」
    moment  : 校准后的当前时间（datetime）
    weekday : 星期几的中文名，如「星期四」
    period  : 时段名，如「下午」；不传就不带
    """
    context = "系统信息：今天的日期是 %s（%s），现在的时刻是 %s" % (
        moment.strftime("%Y-%m-%d"), weekday, moment.strftime("%Y-%m-%d %H:%M"))
    if period:
        context += "（%s）" % period

    user_content = "%s。\n\n我的目标：%s\n\n请按约定的 json 格式给出今天的每小时计划。" % (
        context, answer)
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]

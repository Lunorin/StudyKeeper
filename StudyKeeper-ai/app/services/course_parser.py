#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""课程文本解析：把自由格式的课程描述交给大模型，转成结构化课程列表。

对外入口：
    parse_courses(raw_text) -> {"courses": [...], "failed": [...]}

courses 里每一条：
    {"courseName": "高等数学", "daysOfWeek": [1, 3, 5],
     "startTime": "08:00", "endTime": "09:40"}

failed 里放「没能变成合法课程」的原始行（模型自己报的 + Python 侧剔除的），
交给前端提示用户手动修正。Python 只解析文本：不查库、不调 Java、不做课程去重
（去重由前端处理），也不做图片识别。

关于 sourceLine：提示词要求模型为每条课程额外给出 sourceLine（原样照抄的原文行），
它只用来在 Python 侧校验失败时精准回溯「哪一行没解析好」，不会出现在返回的 course 里
（返回的每条课程固定只有 courseName / daysOfWeek / startTime / endTime 四个字段）。

校验规则（模型不听话也不会把脏数据交出去）：
- courseName 去空白后不能为空，超长截断；
- daysOfWeek 必须是非空数组，元素为 1~7 的整数（数字字符串也接受），去重并升序；
- startTime / endTime 归一成 HH:mm，endTime 必须晚于 startTime；
- 任一条不满足：该条从 courses 剔除，并把对应的原文行放进 failed
  （优先用模型给的 sourceLine；模型没给就退回到启发式回溯）；
- courses 里出现字符串 / 数字这类非对象元素时，原样追加进 failed，不静默丢弃；
- 因为模型可能把多行合并或拆行，这里不做「行数一致性」校验，只管每一条合法不合法；
- courses 与 failed 同时为空（模型什么都没解析出来）视为失败，抛 CourseFormatError。
"""

import json
import re

from app.services import llm_client
from app.services.planner import PlanFormatError, extract_json

DAY_MIN = 1
DAY_MAX = 7
MAX_COURSE_NAME_LENGTH = 100
COURSE_MAX_TOKENS = 4096        # 课程行数可能较多，给足输出空间，避免 JSON 被截断

TIME_PATTERN = re.compile(r"(\d{1,2})\s*[:：]\s*(\d{1,2})")        # 8:00 / 08：00
CLOCK_PATTERN = re.compile(r"(\d{1,2})\s*[点时]\s*(\d{1,2})?")     # 8点40 / 8点

JSON_SHAPE = ('{"courses": [{"courseName": "高等数学", "daysOfWeek": [1, 3, 5], '
              '"startTime": "08:00", "endTime": "09:40", '
              '"sourceLine": "每周一三五 8:00-9:40 高等数学"}], "failed": []}')

SYSTEM_PROMPT = (
    "你是一个课程表解析助手。\n"
    "用户会给一段自由格式的文本，描述他的课程安排。\n"
    "你要把它解析成结构化 json。\n"
    "\n"
    "规则：\n"
    "1. 每一行文本通常描述一门课，可能重复（如同一门课多天）\n"
    "2. 中文星期：一=1 二=2 三=3 四=4 五=5 六=6 日/天=7\n"
    '3. "每周一三五"表示 daysOfWeek: [1, 3, 5]\n'
    "4. 时间格式支持：\n"
    '   - "8:00-9:40" → startTime: "08:00", endTime: "09:40"\n'
    '   - "8点到9点40" → 同上\n'
    '   - "8-9" → "08:00", "09:00"\n'
    "5. 课程名在时间之后或之前，自动识别\n"
    "6. 如果某一行解析失败，放到 failed 数组里，不要强行编造\n"
    "7. 每条课程额外输出 sourceLine：原样照抄这条课程对应的原文那一行，"
    "不要改写、不要合并多行、不要补全\n"
    "8. 只输出 json，不要解释，不要 markdown（不要代码块）\n"
    "\n"
    "输出格式：\n" + JSON_SHAPE
)


class CourseFormatError(PlanFormatError):
    """课程解析结果不符合约定格式（PlanFormatError 的子类，路由层统一映射成 500）。"""


def normalize_hour(text):
    """把 "8:00" / "8：00" / "8点40" / "8点" / "08:00" 归一成 "HH:mm"；失败返回 None。"""
    if text is None or isinstance(text, bool):
        return None

    raw = str(text).strip()
    match = TIME_PATTERN.search(raw)
    if match is None:
        match = CLOCK_PATTERN.search(raw)
        if match is None:
            return None

    hour = int(match.group(1))
    minute = int(match.group(2) or 0)        # "8点" → 8:00
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    return "%02d:%02d" % (hour, minute)


def to_minutes(hour_text):
    """把 "HH:mm" 换算成当天的第几分钟，用来比较先后。"""
    hour, minute = str(hour_text).split(":")
    return int(hour) * 60 + int(minute)


def normalize_days(value):
    """把 daysOfWeek 归一成升序去重的 1~7 整数列表；只要有一个值非法就返回 None。"""
    if isinstance(value, (str, bytes)) or not isinstance(value, (list, tuple)):
        return None

    days = set()
    for item in value:
        if isinstance(item, bool):
            return None
        if isinstance(item, str) and item.strip().isdigit():
            item = int(item.strip())
        if not isinstance(item, int):
            return None
        if not (DAY_MIN <= item <= DAY_MAX):
            return None
        days.add(item)

    if not days:
        return None
    return sorted(days)


def normalize_course(item):
    """把模型返回的一条课程归一化；不合法（缺字段 / 值越界 / 时间倒挂）返回 None。

    返回的字典固定只有 4 个字段：模型为回溯失败行而输出的 sourceLine 在这里被丢掉，
    不会透给调用方。
    """
    if not isinstance(item, dict):
        return None

    name = item.get("courseName")
    if not isinstance(name, str) or not name.strip():
        return None
    name = " ".join(name.split())
    if len(name) > MAX_COURSE_NAME_LENGTH:
        name = name[:MAX_COURSE_NAME_LENGTH].rstrip() + "…"

    days = normalize_days(item.get("daysOfWeek"))
    start = normalize_hour(item.get("startTime"))
    end = normalize_hour(item.get("endTime"))
    if days is None or start is None or end is None:
        return None
    if to_minutes(end) <= to_minutes(start):
        return None

    return {"courseName": name, "daysOfWeek": days,
            "startTime": start, "endTime": end}


def source_line_of(item):
    """取模型给出的 sourceLine（原样照抄的原文行）；没有或空白返回 None。"""
    value = item.get("sourceLine")
    if not isinstance(value, str) or not value.strip():
        return None
    return value.strip()


def locate_source_line(item, raw_lines):
    """启发式回溯：找出这条不合法课程对应原文的哪一行；找不到返回 None。

    只有模型没给 sourceLine 时才用得上。匹配策略：课程名出现在某行里最可靠；
    否则看该行是否包含这条课程的时段数字，例如 item 的 startTime="25:00"
    会拿 "2500" 去原文里找。
    """
    name = str(item.get("courseName") or "").strip()
    wanted_digits = []
    for key in ("startTime", "endTime"):
        digits = re.sub(r"\D", "", str(item.get(key) or ""))
        if digits:
            wanted_digits.append(str(int(digits)))      # "0800" → "800"，兼容 "8:00" 写法

    best_line, best_score = None, 0
    for line in raw_lines:
        if not line.strip():
            continue
        score = 2 if (name and name in line) else 0
        line_digits = re.sub(r"\D", "", line)
        score += sum(1 for digits in wanted_digits if digits and digits in line_digits)
        if score > best_score:
            best_line, best_score = line, score
    return best_line


def describe_stray(item):
    """courses 里混进来的非对象元素 → 一段可直接放进 failed 展示的文本。"""
    if item is None:
        return "null"
    if isinstance(item, str):
        return item.strip()
    if isinstance(item, bool):
        return "true" if item else "false"
    if isinstance(item, (int, float)):
        return str(item)
    return json.dumps(item, ensure_ascii=False)


def dedupe(items):
    """按原顺序去重：同一行可能既被模型报错、又被 Python 剔除。"""
    seen, result = set(), []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


def build_messages(raw_text):
    """拼请求消息：system 说明解析规则与输出格式，user 给出待解析的文本。"""
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "请解析以下课程文本，每行一条：\n" + raw_text},
    ]


def parse_courses(raw_text):
    """对外入口：调模型 → 校验 → 返回 {"courses": [...], "failed": [...]}。"""
    if not isinstance(raw_text, str) or not raw_text.strip():
        # 路由层已用 Pydantic 拦过，这里再防一层
        raise CourseFormatError("rawText 不能为空")

    raw_lines = raw_text.splitlines()
    model_output = llm_client.chat_json(build_messages(raw_text),
                                        max_tokens=COURSE_MAX_TOKENS)
    data = extract_json(model_output)          # 非法 JSON 会抛 PlanFormatError

    if not isinstance(data, dict):
        raise CourseFormatError("JSON 顶层必须是对象，收到 {}".format(type(data).__name__))

    raw_courses = data.get("courses")
    if not isinstance(raw_courses, list):
        raise CourseFormatError("JSON 里缺少 courses 数组（约定格式：{}）".format(JSON_SHAPE))

    courses, failed = [], []
    for item in raw_courses:
        if not isinstance(item, dict):
            # 模型偶尔把原始行/数字直接塞进 courses：原样当失败行，别静默丢掉
            text = describe_stray(item)
            if text:
                failed.append(text)
            continue

        course = normalize_course(item)
        if course is not None:
            courses.append(course)
            continue

        # 不合格的：从 courses 剔除，把原文那一行放进 failed
        # （优先用模型给的 sourceLine 精准回溯，没有才走启发式）
        source = source_line_of(item) or locate_source_line(item, raw_lines)
        if source:
            failed.append(source)
        else:
            failed.append("（无法定位原文）{}".format(json.dumps(item, ensure_ascii=False)))

    # 模型自己报的失败行也带上（它看到的是原文，比我们回溯更准）
    for entry in data.get("failed") or []:
        if isinstance(entry, str) and entry.strip():
            failed.append(entry.strip())

    failed = dedupe(failed)
    if not courses and not failed:
        raise CourseFormatError("模型没有解析出任何课程，也没有给出失败行")

    return {"courses": courses, "failed": failed}

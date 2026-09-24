#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""学习记录的持久化：读写 data/records.json 与 data/state.json（纯标准库）。

单条记录的结构：
    {
        "date":          "2026-09-17",     # 记录属于哪一天
        "time":          "12:07:41",       # 记录产生的时间
        "type":          "report",         # report = 已汇报 / missed = 未汇报
        "slot":          "12:00",          # 对应的提醒时间点；主动汇报时为 None
        "content":       "背了 30 个单词",   # 未汇报记录为空字符串
        "gap_seconds":   11400,            # 距上一次汇报差了多少秒；没有上一次则为 None
        "delay_seconds": 461               # 比本次提醒点迟了多少秒；主动汇报时为 None
    }

data/state.json 存跨进程的“上次互动时间”，用来算时间差：
    {
        "version": 1,
        "last_user_at":         "2026-09-17 11:05:00",   # 本次开口时间
        "previous_user_at":     "2026-09-17 10:20:00",   # 上一次开口时间
        "last_user_gap_seconds": 2700,                   # 两次开口之间的时间差
        "last_assistant_at":    "2026-09-17 11:05:03"    # 上次模型回复时间
    }

data/plans.json 存每天的任务计划，按日期分组、一天一份（同一天再规划就覆盖当天那份）：
    {
        "version": 1,
        "plans": {
            "2026-09-17": {
                "date":       "2026-09-17",
                "created_at": "2026-09-17 09:05:12",
                "source":     "背单词、写完高数作业",
                "tasks": [
                    {"hour": "09:00", "task": "背 50 个单词"},
                    {"hour": "10:00", "task": "写完高数作业第 3 章"}
                ]
            }
        }
    }

写入策略：先写同目录临时文件、flush + fsync，再用 os.replace 原子替换，
所以进程中途被杀也不会把已有数据写坏。
"""

import json
import os
import tempfile
import threading
from datetime import datetime

RECORDS_DIR_NAME = "data"
RECORDS_FILE_NAME = "records.json"
STATE_FILE_NAME = "state.json"
PLANS_FILE_NAME = "plans.json"
RECORDS_RELATIVE = RECORDS_DIR_NAME + "/" + RECORDS_FILE_NAME  # 仅用于显示
STATE_RELATIVE = RECORDS_DIR_NAME + "/" + STATE_FILE_NAME
PLANS_RELATIVE = RECORDS_DIR_NAME + "/" + PLANS_FILE_NAME

TYPE_REPORT = "report"
TYPE_MISSED = "missed"

# 主线程（记录汇报）和提醒线程（记录未汇报）会同时写文件，需要串行化
_lock = threading.Lock()


def records_path(base_dir):
    """返回 data/records.json 的绝对路径。"""
    return os.path.join(base_dir, RECORDS_DIR_NAME, RECORDS_FILE_NAME)


def state_path(base_dir):
    """返回 data/state.json 的绝对路径。"""
    return os.path.join(base_dir, RECORDS_DIR_NAME, STATE_FILE_NAME)


def plans_path(base_dir):
    """返回 data/plans.json 的绝对路径。"""
    return os.path.join(base_dir, RECORDS_DIR_NAME, PLANS_FILE_NAME)


def ensure_file(path):
    """确保记录文件存在：首次运行就建好空文件，方便直接打开查看。

    文件已存在时绝不改动；目录不可写等错误静默忽略（不影响对话）。
    """
    if os.path.isfile(path):
        return
    try:
        with _lock:
            if not os.path.isfile(path):
                _write(path, [])
    except OSError:
        pass


def ensure_plans_file(path):
    """确保计划文件存在：首次运行就建好空文件，方便直接打开查看。

    文件已存在时绝不改动；目录不可写等错误静默忽略（不影响对话）。
    """
    if os.path.isfile(path):
        return
    try:
        with _lock:
            if not os.path.isfile(path):
                _write_json(path, {"version": 1, "plans": {}})
    except OSError:
        pass


def load_records(path):
    """读取全部记录列表；文件缺失或不可用时返回空列表，绝不抛异常。"""
    if not os.path.isfile(path):
        return []

    try:
        with open(path, "r", encoding="utf-8-sig") as handle:
            data = json.load(handle)
    except (OSError, UnicodeDecodeError):
        return []
    except ValueError:
        # JSON 坏了：把原文件挪到一边，避免后面写入直接覆盖掉用户数据
        _quarantine(path)
        return []

    if isinstance(data, dict):
        records = data.get("records")
    else:
        records = data  # 兼容直接写成数组的文件
    if not isinstance(records, list):
        return []
    return [item for item in records if isinstance(item, dict)]


def append_record(path, record_type, content="", slot=None, when=None,
                  gap_seconds=None, delay_seconds=None):
    """追加一条记录并立即落盘，返回这条记录。

    gap_seconds   : 距上一次汇报的时间差（秒）；还没有历史汇报时传 None
    delay_seconds : 比本次提醒时间点迟了多久（秒）；主动汇报时传 None
    """
    when = when or datetime.now()
    record = {
        "date": when.strftime("%Y-%m-%d"),
        "time": when.strftime("%H:%M:%S"),
        "type": record_type,
        "slot": slot,
        "content": content,
        "gap_seconds": round_seconds(gap_seconds),
        "delay_seconds": round_seconds(delay_seconds),
    }
    with _lock:
        records = load_records(path)
        records.append(record)
        _write(path, records)
    return record


def load_state(path):
    """读取 data/state.json；文件缺失、损坏或类型不对时返回空字典。"""
    if not os.path.isfile(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8-sig") as handle:
            data = json.load(handle)
    except (OSError, UnicodeDecodeError, ValueError):
        return {}
    if not isinstance(data, dict):
        return {}
    data.pop("version", None)
    return data


def save_state(path, state):
    """原子写入 data/state.json。"""
    payload = {"version": 1}
    payload.update(state)
    with _lock:
        _write_json(path, payload)


def load_plans(path):
    """读取全部日计划，返回 {日期: 计划} 字典；文件缺失或不可用时返回空字典。

    JSON 坏了就把原文件挪到一边（和记录文件一样），避免后面写入把用户数据覆盖掉。
    """
    if not os.path.isfile(path):
        return {}

    try:
        with open(path, "r", encoding="utf-8-sig") as handle:
            data = json.load(handle)
    except (OSError, UnicodeDecodeError):
        return {}
    except ValueError:
        _quarantine(path)
        return {}

    if not isinstance(data, dict):
        return {}
    plans = data.get("plans")
    if not isinstance(plans, dict):
        return {}
    return {key: value for key, value in plans.items() if isinstance(value, dict)}


def get_plan(path, date_str):
    """取某一天的日计划；没有规划过则返回 None。"""
    return load_plans(path).get(date_str)


def save_plan(path, plan):
    """写入某一天的日计划并立即落盘，返回这份计划（同一天再写就是覆盖）。

    plan 必须带 date 字段；其余字段原样存进 data/plans.json。
    """
    date_str = plan.get("date")
    if not date_str:
        raise ValueError("计划里必须有 date 字段")
    with _lock:
        plans = load_plans(path)
        plans[date_str] = plan
        _write_json(path, {"version": 1, "plans": plans})
    return plan


def parse_timestamp(text):
    """解析 "YYYY-MM-DD HH:MM:SS" 或 "YYYY-MM-DD HH:MM"；失败返回 None。"""
    if isinstance(text, datetime):
        return text
    if not text:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(str(text), fmt)
        except ValueError:
            continue
    return None


def record_datetime(record):
    """把一条记录还原成 datetime；字段缺失或格式不对时返回 None。"""
    date_str = record.get("date")
    time_str = record.get("time")
    if not date_str or not time_str:
        return None
    return parse_timestamp("%s %s" % (date_str, time_str))


def find_last(records, record_type):
    """返回最后一条指定类型的记录；没有则返回 None。"""
    for item in reversed(records):
        if item.get("type") == record_type:
            return item
    return None


def round_seconds(value):
    """把秒数规整成整数；None 原样返回，非法值也返回 None。"""
    if value is None:
        return None
    try:
        return int(round(float(value)))
    except (TypeError, ValueError):
        return None


def select_by_date(records, date_str):
    """挑出某一天的记录，保持原有时间顺序。"""
    return [item for item in records if item.get("date") == date_str]


def count_by_type(records):
    """返回 (汇报条数, 未汇报条数)。"""
    reports = 0
    missed = 0
    for item in records:
        if item.get("type") == TYPE_MISSED:
            missed += 1
        elif item.get("type") == TYPE_REPORT:
            reports += 1
    return reports, missed


def _write(path, records):
    """原子写入整个记录文件。"""
    _write_json(path, {"version": 1, "records": records})


def _write_json(path, payload):
    """把 payload 序列化后原子写入 path：临时文件 → flush + fsync → os.replace。"""
    directory = os.path.dirname(path) or "."
    os.makedirs(directory, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, indent=2)

    handle = None
    temp_path = None
    try:
        fd, temp_path = tempfile.mkstemp(dir=directory, prefix=".records-", suffix=".tmp")
        handle = os.fdopen(fd, "w", encoding="utf-8")
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())
        handle.close()
        handle = None
        os.replace(temp_path, path)
        temp_path = None
    finally:
        if handle is not None:
            try:
                handle.close()
            except OSError:
                pass
        if temp_path is not None and os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass


def _quarantine(path):
    """把损坏的记录文件改名留存，返回备份路径（失败时返回 None）。"""
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = "{}.corrupt-{}".format(path, stamp)
    try:
        os.replace(path, backup)
    except OSError:
        return None
    return backup

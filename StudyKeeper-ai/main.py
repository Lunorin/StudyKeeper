#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""StudyKeeper 第三版：会定时提醒你汇报学习进度、并帮你安排今日计划的命令行助手。

启动方式（在项目根目录）：
    python main.py

依赖：零第三方依赖，只用 Python 标准库（urllib / json / socket / struct / threading）。

配置：API Key 优先取环境变量 DEEPSEEK_API_KEY；该变量为空时，
    会自动读取本文件同目录下的 .env（没有 .env 也能正常运行）。

    PowerShell : $env:DEEPSEEK_API_KEY = "sk-你的key"
    或写在 .env 里：DEEPSEEK_API_KEY=sk-你的key

功能：
- 每天第一次聊天时，如果今天还没有计划，就先问你「今天要做什么」；你答一句，模型把它
  拆成「每小时做什么」的计划，存进 data/plans.json 并打印给你看，然后回到正常聊天；
- 每天 12:00 / 17:00 / 22:00 自动提醒你汇报学习进度；提醒的同时你照样能随时打字聊天；
- 输入「汇报 <内容>」记录一次汇报；到点后超过宽限期还没汇报，自动记一次「未汇报」；
- 输入「查询」查看今天的记录；记录保存在同目录的 data/records.json；
- 启动时用 NTP 校准系统时间（纯标准库 SNTP；UDP 123 不通就自动退回系统时钟）；
- 每次请求都实时注入「当前时间 / 星期 / 时段 / 今日提醒进度 / 今日记录 / 上次互动与
  上次汇报距今多久」，所以模型既知道现在几点，也知道距上一件事过了多久；
- 每条汇报会记下「距上次汇报的时间差」和「比提醒点迟了多久」；跨进程的互动时间差
  存在 data/state.json；
- 其余输入按普通聊天处理，交给 DeepSeek 回复（只带最近 12 条历史）。

模块划分：
    main.py       入口：配置、命令分派、对话循环、今日规划流程
    clock.py      NTP 时间校准（零依赖 SNTP 客户端）
    planner.py    今日计划：规划提示词、模型 JSON 的校验与渲染
    scheduler.py  定时提醒（守护线程，不阻塞 input）
    store.py      data/records.json / data/state.json / data/plans.json 的读写（原子写入）
"""

import json
import os
import socket
import sys
import threading
import urllib.error
import urllib.request
from datetime import datetime

import clock
import planner
import scheduler
import store

# ---------------------------------------------------------------------------
# 配置区：需要调整时只改这一段
# ---------------------------------------------------------------------------

BASE_URL = "https://api.deepseek.com/v1"
CHAT_ENDPOINT = BASE_URL + "/chat/completions"
MODEL = "deepseek-chat"
API_KEY_ENV = "DEEPSEEK_API_KEY"
ENV_FILE_NAME = ".env"  # 环境变量为空时，会尝试读取 main.py 同目录下的这个文件

SYSTEM_PROMPT = "你是一个学习监督助手，回复简短、温和，鼓励学生并追问学习进度。"

MAX_HISTORY = 12        # 每次请求只把最近 12 条历史消息带给模型
TEMPERATURE = 0.6       # 略微收敛，让回复更稳定、更简短
TIMEOUT_SECONDS = 60    # 单次请求超时时间

EXIT_COMMANDS = ("exit", "quit")
PROMPT = "你  > "
BANNER_WIDTH = 46

# -- 第二版新增：定时提醒与学习记录 --
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RECORDS_PATH = store.records_path(BASE_DIR)        # 实际文件：<项目>/data/records.json
RECORDS_DISPLAY = store.RECORDS_RELATIVE           # 界面上显示的相对路径

REMINDER_CHECKPOINTS = ("12:00", "17:00", "22:00")  # 每天这几个点提醒汇报
GRACE_MINUTES = 30                                  # 到点后多久还没汇报，就记一次「未汇报」
REMINDER_POLL_SECONDS = 5                           # 提醒线程轮询间隔
REPORT_COMMAND = "汇报"                              # 「汇报 <内容>」记录一次汇报
QUERY_COMMAND = "查询"                               # 「查询」查看今天的记录
REPORT_SEPARATORS = " \t\u3000:：，,、"               # 指令与内容之间的分隔符
REPORT_TO_MODEL = True                              # 汇报后是否也交给模型点评一句

# -- NTP 时间校准（零依赖 SNTP 客户端，见 clock.py）--
NTP_ENABLED = True                                  # 关掉就纯用系统时钟
NTP_SERVERS = clock.DEFAULT_SERVERS                 # 阿里 / 腾讯 / 两个公共池
NTP_TIMEOUT_SECONDS = 1.5                           # 单台服务器超时
NTP_BUDGET_SECONDS = 3.0                            # 整轮校准预算，避免启动被卡住

# 全局时钟：main() 启动时调用 CLOCK.sync()，之后全程序一律用 CLOCK.now()
CLOCK = clock.Clock(servers=NTP_SERVERS, timeout=NTP_TIMEOUT_SECONDS,
                    budget=NTP_BUDGET_SECONDS, enabled=NTP_ENABLED)

# -- 交互时间差（跨进程记录）--
STATE_PATH = store.state_path(BASE_DIR)             # <项目>/data/state.json
STATE_DISPLAY = store.STATE_RELATIVE

# -- 第三版新增：今日任务规划 --
PLANS_PATH = store.plans_path(BASE_DIR)             # <项目>/data/plans.json
PLANS_DISPLAY = store.PLANS_RELATIVE
PLAN_TEMPERATURE = 0.3                              # 规划要求严格 JSON，温度调低更稳
PLAN_JSON_MODE = True                               # 用接口的 JSON 模式，强制模型返回 JSON 对象
PLAN_ANSWER_HINT = "直接说一句就行，例如：背 300 个单词、写完高数作业、复习线性代数"


# ---------------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------------

# 主线程和提醒线程都会往控制台写字，共用一把锁避免输出互相插队
CONSOLE_LOCK = threading.Lock()


def today_str(moment=None):
    """今天的日期字符串，形如 2026-09-17（用 NTP 校准后的时钟）。"""
    return (moment or CLOCK.now()).strftime("%Y-%m-%d")


def say(text=""):
    """线程安全地打印一行（主线程与提醒线程共用）。"""
    with CONSOLE_LOCK:
        print(text)
        sys.stdout.flush()


def print_prompt():
    """提醒/记录打断之后，把输入提示补回屏幕上。"""
    with CONSOLE_LOCK:
        print(PROMPT, end="", flush=True)


def env_file_path():
    """返回 .env 的绝对路径：以 main.py 所在目录为准，不受启动目录影响。"""
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(here, ENV_FILE_NAME)


def load_env_file():
    """把 .env 里的变量补进 os.environ，返回本次实际写入的变量名列表。

    规则：
    - 真实环境变量优先：已存在且非空的变量绝不会被 .env 覆盖；
    - 逐行解析 KEY=VALUE，忽略空行、以 # 开头的注释行、以及不含 = 的行；
    - 变量名两端的空格被去掉，支持 "export KEY=VALUE" 写法；
    - 值两端的成对引号会被去掉；
    - .env 不存在或读不出来时静默跳过，不影响程序启动；
    - 纯标准库实现，不引入 python-dotenv 之类的依赖。
    """
    path = env_file_path()
    if not os.path.isfile(path):
        return []

    try:
        with open(path, "r", encoding="utf-8-sig") as handle:
            lines = handle.readlines()
    except (OSError, UnicodeDecodeError):
        return []

    loaded = []
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        name, _, value = line.partition("=")
        name = name.strip()
        if name.startswith("export "):
            name = name[len("export "):].strip()
        if not name or " " in name:
            continue

        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]

        if not os.environ.get(name):  # 空值等同于没设置，允许用 .env 补上
            os.environ[name] = value
            loaded.append(name)
    return loaded


def read_api_key():
    """读取 API Key：真实环境变量优先，其次同目录 .env；都为空时返回 None。"""
    api_key = os.environ.get(API_KEY_ENV)
    if api_key is None:
        return None
    api_key = api_key.strip()
    return api_key or None


WEEKDAY_NAMES = ("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

# (起始小时, 结束小时, 时段名)，左闭右开
PERIODS = (
    (0, 5, "凌晨"),
    (5, 8, "清晨"),
    (8, 11, "上午"),
    (11, 13, "中午"),
    (13, 17, "下午"),
    (17, 19, "傍晚"),
    (19, 23, "晚上"),
    (23, 24, "深夜"),
)

# 让模型明白这段是系统给的环境信息，而不是用户说的话
CLOCK_HEADER = "以下是系统提供的实时环境信息（不是用户发言；请据此判断现在的时间和今天的进度，不要说自己看不到时间）："


def period_of_day(moment):
    """把时刻翻译成时段名，如「晚上」。"""
    for start, end, name in PERIODS:
        if start <= moment.hour < end:
            return name
    return "今天"


def slot_datetime(label, moment):
    """把提醒时间点还原成当天的 datetime；解析失败返回 None。"""
    try:
        hour, minute, second = scheduler.parse_checkpoint(label)
    except ValueError:
        return None
    return moment.replace(hour=hour, minute=minute, second=second, microsecond=0)


def checkpoint_state(moment):
    """返回 (今天已经过去的提醒点, 今天下一个提醒点)。"""
    entries = []
    for label in REMINDER_CHECKPOINTS:
        try:
            entries.append((scheduler.parse_checkpoint(label), label))
        except ValueError:
            continue
    entries.sort()

    passed = []
    upcoming = None
    for (hour, minute, second), label in entries:
        due = moment.replace(hour=hour, minute=minute, second=second, microsecond=0)
        if moment >= due:
            passed.append(label)
        elif upcoming is None:
            upcoming = label
    return passed, upcoming


def note_interaction(moment=None):
    """记下「这次开口」的时间，并算出距上次开口的时间差（跨进程持久化）。

    返回值就是这次记录下来的时间差（秒）；本次运行第一次开口时返回 None。
    """
    moment = moment or CLOCK.now()
    state = store.load_state(STATE_PATH)
    previous = store.parse_timestamp(state.get("last_user_at"))

    gap = None
    if previous is not None:
        gap = max(0.0, (moment - previous).total_seconds())

    state["previous_user_at"] = previous.strftime("%Y-%m-%d %H:%M:%S") if previous else None
    state["last_user_at"] = moment.strftime("%Y-%m-%d %H:%M:%S")
    state["last_user_gap_seconds"] = store.round_seconds(gap)
    try:
        store.save_state(STATE_PATH, state)
    except OSError as exc:
        say(f"[警告] 写入 {STATE_DISPLAY} 失败：{exc}")
    return gap


def last_report_gap_seconds(moment=None):
    """距上一次汇报过了多少秒；还没有任何汇报记录时返回 None。"""
    moment = moment or CLOCK.now()
    last = store.find_last(store.load_records(RECORDS_PATH), store.TYPE_REPORT)
    if last is None:
        return None
    last_at = store.record_datetime(last)
    if last_at is None:
        return None
    return max(0.0, (moment - last_at).total_seconds())


def time_context(moment=None):
    """给模型看的实时上下文；每次调用都按当下时刻重新生成。"""
    moment = moment or CLOCK.now()
    weekday = WEEKDAY_NAMES[moment.weekday()]
    lines = [f"当前时间：{moment.strftime('%Y-%m-%d %H:%M')}（{weekday}），{period_of_day(moment)}"]

    # 距上一次开口过了多久（由 note_interaction 记录，跨进程有效）
    state = store.load_state(STATE_PATH)
    previous_text = state.get("previous_user_at")
    gap_seconds = state.get("last_user_gap_seconds")
    if previous_text and gap_seconds is not None:
        lines.append(f"上次互动：上一条消息在 {previous_text}，"
                     f"本次开口与其相隔 {clock.format_duration(gap_seconds)}")
    else:
        lines.append("上次互动：这是当前记录里的第一次开口")

    # 距上一次汇报过了多久，以及那条汇报自己的两个时间差
    last_report = store.find_last(store.load_records(RECORDS_PATH), store.TYPE_REPORT)
    if last_report is None:
        lines.append("上次汇报：还没有任何汇报记录")
    else:
        last_at = store.record_datetime(last_report)
        if last_at is None:
            lines.append("上次汇报：有记录但时间无法解析")
        else:
            extra = ""
            if last_report.get("gap_seconds") is not None:
                extra += "，距其上一次汇报 %s" % clock.format_duration(last_report["gap_seconds"])
            if last_report.get("delay_seconds") is not None:
                extra += "，比提醒点迟 %s" % clock.format_duration(last_report["delay_seconds"])
            ago = clock.format_duration((moment - last_at).total_seconds())
            lines.append(f"上次汇报：{last_at.strftime('%Y-%m-%d %H:%M')}，距今 {ago}{extra}")

    # 今日提醒进度 + 距下一个提醒点还有多久
    if REMINDER_CHECKPOINTS:
        passed, upcoming = checkpoint_state(moment)
        passed_text = "、".join(passed) if passed else "无"
        checkpoints_text = "、".join(REMINDER_CHECKPOINTS)
        if upcoming:
            due = slot_datetime(upcoming, moment)
            remain = clock.format_duration((due - moment).total_seconds()) if due else "未知"
            lines.append(f"今日提醒点：{checkpoints_text}；已过 {passed_text}，"
                         f"下一个 {upcoming}（还有 {remain}）")
        else:
            lines.append(f"今日提醒点：{checkpoints_text}；今天已全部过完")

    today_records = store.select_by_date(store.load_records(RECORDS_PATH), today_str(moment))
    reports, missed = store.count_by_type(today_records)
    lines.append(f"今日汇报记录：汇报 {reports} 次，未汇报 {missed} 次")
    return "\n".join(lines)


def build_messages(history):
    """拼装请求体里的 messages：系统提示词（含实时时钟）+ 最近 MAX_HISTORY 条历史消息。

    history 中 user 和 assistant 消息混在一起，"最近 12 条" 指的是这 12 条。
    时钟上下文每次请求都重新生成，所以模型看到的永远是"现在"，也不会被历史里的旧时间带偏。
    返回新列表，不会修改传入的 history。
    """
    system_content = SYSTEM_PROMPT + "\n\n" + CLOCK_HEADER + "\n" + time_context()
    messages = [{"role": "system", "content": system_content}]
    if MAX_HISTORY > 0:
        messages.extend(history[-MAX_HISTORY:])
    return messages


def print_config_hint():
    """API Key 缺失时给出配置方法。"""
    say(f"未检测到 API Key：环境变量 {API_KEY_ENV} 为空，{ENV_FILE_NAME} 里也没有可用值。")
    say(f"（已查找的 {ENV_FILE_NAME} 路径：{env_file_path()}）")
    say("任选一种方式配置，然后重新运行 main.py：")
    say(f'  1) PowerShell : $env:{API_KEY_ENV} = "sk-你的key"')
    say(f'  2) CMD        : set {API_KEY_ENV}=sk-你的key')
    say(f'  3) Linux/macOS: export {API_KEY_ENV}=sk-你的key')
    say(f"  4) 在 {ENV_FILE_NAME} 里写一行：{API_KEY_ENV}=sk-你的key")


def use_utf8_output():
    """尽量把标准输出切到 UTF-8，避免 Windows 控制台中文乱码。

    只处理 stdout / stderr，不动 stdin，以免影响控制台的中文输入。
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            pass


# ---------------------------------------------------------------------------
# 模型调用（OpenAI 兼容接口）
# ---------------------------------------------------------------------------

def describe_http_error(exc):
    """把 HTTPError 转成一句可直接展示的中文错误信息。"""
    hints = {
        400: "请求格式不被接受",
        401: "API Key 无效或未授权",
        402: "账户余额不足",
        403: "无权访问该模型",
        404: "接口地址或模型不存在",
        422: "请求参数有误",
        429: "请求过于频繁，请稍后再试",
        500: "服务端内部错误",
        502: "网关错误",
        503: "服务暂不可用",
    }

    detail = ""
    try:
        raw = exc.read()
    except Exception:
        raw = b""
    if raw:
        text = raw.decode("utf-8", "replace").strip()
        try:
            data = json.loads(text)
        except ValueError:
            data = None
        if isinstance(data, dict):
            error = data.get("error")
            if isinstance(error, dict):
                detail = str(error.get("message") or "").strip()
            if not detail and data.get("message"):
                detail = str(data["message"]).strip()
        if not detail:
            detail = text[:200]

    parts = [f"请求失败：HTTP {exc.code}"]
    hint = hints.get(exc.code)
    if hint:
        parts.append(f"（{hint}）")
    message = " ".join(parts)
    if detail:
        message += f" - {detail}"
    return message


def call_model(messages, api_key, temperature=TEMPERATURE, json_mode=False):
    """调用 OpenAI 兼容的 chat/completions 接口，返回助手回复文本。

    temperature : 采样温度，默认是聊天用的 TEMPERATURE；规划计划时传更低的 PLAN_TEMPERATURE
    json_mode   : True 时带上 response_format，让接口保证返回 JSON 对象（规划计划用）

    任何失败都抛出 RuntimeError，其消息已是可直接打印的中文提示。
    """
    payload = {
        "model": MODEL,
        "messages": messages,
        "temperature": temperature,
        "stream": False,
    }
    if json_mode:
        payload["response_format"] = {"type": "json_object"}
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        CHAT_ENDPOINT,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "Authorization": "Bearer " + api_key,
        },
    )

    # 1) 发请求：网络层错误统一转成 RuntimeError
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            raw = response.read()
    except urllib.error.HTTPError as exc:
        raise RuntimeError(describe_http_error(exc))
    except socket.timeout:
        raise RuntimeError(f"请求超时（超过 {TIMEOUT_SECONDS} 秒），请检查网络后重试。")
    except urllib.error.URLError as exc:
        reason = getattr(exc, "reason", exc)
        if isinstance(reason, socket.timeout):
            raise RuntimeError(f"请求超时（超过 {TIMEOUT_SECONDS} 秒），请检查网络后重试。")
        raise RuntimeError(f"网络连接失败：{reason}")
    except OSError as exc:
        raise RuntimeError(f"网络异常：{exc}")

    # 2) 解析响应：格式不对也不让程序崩
    try:
        data = json.loads(raw.decode("utf-8", "replace"))
    except ValueError:
        raise RuntimeError("响应不是合法的 JSON，无法解析模型返回内容。")

    if not isinstance(data, dict):
        raise RuntimeError("响应结构异常：顶层不是 JSON 对象。")

    error = data.get("error")
    if isinstance(error, dict):
        raise RuntimeError("接口返回错误：" + str(error.get("message") or error))

    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        raise RuntimeError("接口没有返回任何回复内容（choices 为空）。")

    first = choices[0] if isinstance(choices[0], dict) else {}
    message = first.get("message")
    if not isinstance(message, dict):
        raise RuntimeError("响应结构异常：缺少 message 字段。")

    content = message.get("content")
    if not isinstance(content, str) or not content.strip():
        raise RuntimeError("模型返回了空回复，请换种说法再问一次。")
    return content.strip()


def chat_once(history, api_key, user_message, echo=True):
    """把一条用户消息交给模型，成功后写入历史并打印回复。

    失败时打印错误并撤回本轮消息（不污染上下文），返回 True/False。
    """
    history.append({"role": "user", "content": user_message})
    try:
        reply = call_model(build_messages(history), api_key)
    except RuntimeError as exc:
        history.pop()
        say(f"[模型调用失败] {exc}")
        say("（本轮消息未记入对话历史，你可以直接重新输入）")
        return False
    except Exception as exc:  # 兜底：任何意外都不让程序崩溃
        history.pop()
        say(f"[未预期的错误] {type(exc).__name__}: {exc}")
        return False

    history.append({"role": "assistant", "content": reply})
    if echo:
        say(f"助手 > {reply}")
    return True


def split_report(text):
    """识别「汇报 <内容>」指令：返回内容（可能是空串），不是指令则返回 None。

    「汇报」后面必须跟分隔符或直接结束，所以像「汇报一下今天的进度」这种
    自然语句不会被当成指令，仍然按普通聊天交给模型。
    """
    if not text.startswith(REPORT_COMMAND):
        return None
    rest = text[len(REPORT_COMMAND):]
    if not rest:
        return ""
    if rest[0] not in REPORT_SEPARATORS:
        return None
    return rest.lstrip(REPORT_SEPARATORS).strip()


# ---------------------------------------------------------------------------
# 定时提醒与记录（回调会被提醒线程调用）
# ---------------------------------------------------------------------------

def show_reminder(slot, deadline):
    """到点提醒（在提醒线程里执行）。"""
    with CONSOLE_LOCK:
        print()
        print("=" * BANNER_WIDTH)
        print(f"[提醒 {slot}] 该汇报学习进度啦！")
        print(f" 输入：{REPORT_COMMAND} <你刚才学了什么>")
        print(f" 到 {deadline.strftime('%H:%M')} 还没汇报，会记一次「未汇报」。")
        print("=" * BANNER_WIDTH)
        print(PROMPT, end="", flush=True)


def handle_missed(slot):
    """宽限期内没收到汇报，记一次「未汇报」（在提醒线程里执行）。"""
    moment = CLOCK.now()
    due = slot_datetime(slot, moment)
    delay = max(0.0, (moment - due).total_seconds()) if due else None

    try:
        store.append_record(RECORDS_PATH, store.TYPE_MISSED, slot=slot, when=moment,
                            delay_seconds=delay)
    except OSError as exc:
        say(f"\n[警告] 写入 {RECORDS_DISPLAY} 失败：{exc}")
        print_prompt()
        return

    late = "（超时 %s）" % clock.format_duration(delay) if delay is not None else ""
    say(f"\n[未汇报] {slot} 的提醒没收到汇报{late}，已记入 {RECORDS_DISPLAY}。")
    print_prompt()


def print_today_summary():
    """启动时汇报一下今天的记录情况。"""
    records = store.select_by_date(store.load_records(RECORDS_PATH), today_str())
    reports, missed = store.count_by_type(records)
    if records:
        say(f" 今天已记录：汇报 {reports} 次，未汇报 {missed} 次")
    else:
        say(" 今天还没有记录")


def show_today(reminder):
    """「查询」：打印今天的全部记录。"""
    today = today_str()
    records = store.select_by_date(store.load_records(RECORDS_PATH), today)
    reports, missed = store.count_by_type(records)

    say("")
    say(f"[查询] {today}：共 {len(records)} 条（汇报 {reports} 次，未汇报 {missed} 次）")
    if not records:
        say(f" 今天还没有记录，输入「{REPORT_COMMAND} <内容>」就能记一条。")
    for record in records:
        slot = record.get("slot")
        tag = f"对应 {slot} 提醒" if slot else "主动汇报"
        when_text = record.get("time", "--:--:--")
        detail = []
        if record.get("delay_seconds") is not None:
            detail.append("迟 %s" % clock.format_duration(record["delay_seconds"]))
        if record.get("gap_seconds") is not None:
            detail.append("距上次汇报 %s" % clock.format_duration(record["gap_seconds"]))
        suffix = ("（%s）" % "，".join(detail)) if detail else ""
        if record.get("type") == store.TYPE_MISSED:
            say(f"  {when_text}  [未汇报]  {tag}{suffix}")
        else:
            say(f"  {when_text}  [汇报]    {tag}{suffix} —— {record.get('content', '')}")
    nxt = reminder.next_checkpoint()
    say(f" 下一次提醒：{nxt}" if nxt else " 下一次提醒：今天的提醒已全部到点")
    say(f" 记录文件：{RECORDS_DISPLAY}")


# ---------------------------------------------------------------------------
# 今日任务规划（第三版新增）
# ---------------------------------------------------------------------------

def today_plan(moment=None):
    """读今天的计划；今天还没规划过（或文件读不出来）时返回 None。"""
    return store.get_plan(PLANS_PATH, today_str(moment))


def prompt_plan():
    """进入规划模式时，先问你今天要做什么。"""
    moment = CLOCK.now()
    weekday = WEEKDAY_NAMES[moment.weekday()]
    say("")
    say(f"[规划] 今天（{today_str(moment)} {weekday}）还没有计划。")
    say(f" 今天要做什么？{PLAN_ANSWER_HINT}")
    say(f" 答一次就生成「每小时做什么」，存进 {PLANS_DISPLAY}，然后回到正常聊天。")


def build_plan(answer, api_key):
    """把你的回答交给模型拆成每小时计划，成功后存盘；返回计划字典，失败返回 None。

    这次请求（含你的回答）不会写进聊天历史，免得把「只输出 JSON」的指令带进后面的闲聊。
    """
    moment = CLOCK.now()
    date_str = today_str(moment)
    messages = planner.build_messages(answer, moment, WEEKDAY_NAMES[moment.weekday()],
                                      period_of_day(moment))

    say("[规划] 正在把今天的目标拆成每小时计划……")
    try:
        raw = call_model(messages, api_key, temperature=PLAN_TEMPERATURE,
                         json_mode=PLAN_JSON_MODE)
    except RuntimeError as exc:
        say(f"[模型调用失败] {exc}")
        return None
    except Exception as exc:  # 兜底：任何意外都不让程序崩溃
        say(f"[未预期的错误] {type(exc).__name__}: {exc}")
        return None

    try:
        plan = planner.parse_plan(raw, date_str)
    except planner.PlanFormatError as exc:
        say(f"[计划格式不对] {exc}")
        return None

    # 模型只管 date / tasks，这里再补上生成时间和你的原话，方便以后回看
    plan["created_at"] = moment.strftime("%Y-%m-%d %H:%M:%S")
    plan["source"] = answer
    try:
        store.save_plan(PLANS_PATH, plan)
    except OSError as exc:
        say(f"[警告] 写入 {PLANS_DISPLAY} 失败：{exc}")
    return plan


def show_plan(plan):
    """把计划用可读的方式打印出来。"""
    moment = CLOCK.now()
    say("")
    for line in planner.render_plan(plan, weekday=WEEKDAY_NAMES[moment.weekday()]):
        say(line)
    say(f" 计划文件：{PLANS_DISPLAY}")


# ---------------------------------------------------------------------------
# 交互主循环
# ---------------------------------------------------------------------------

def print_banner(key_source, reminder, has_plan):
    """启动时打印一行式说明。"""
    line = "=" * BANNER_WIDTH
    say(line)
    say(" StudyKeeper · 学习监督助手（第三版）")
    say(f" 模型：{MODEL}")
    say(f" API Key：{key_source}")
    say(f" 时间：{CLOCK.describe()}")
    say(f" 上下文：仅带最近 {MAX_HISTORY} 条历史消息")
    say(f" 提醒：{reminder.checkpoints_text()}（超 {GRACE_MINUTES} 分钟未汇报记一次「未汇报」）")
    plan_state = "今天已有计划" if has_plan else "今天还没有计划，先问你今天要做什么"
    say(f" 计划：{PLANS_DISPLAY}（{plan_state}）")
    say(f" 命令：{REPORT_COMMAND} <内容> 记录汇报 / {QUERY_COMMAND} 看今天记录 / exit 退出")
    print_today_summary()
    nxt = reminder.next_checkpoint()
    say(f" 下一次提醒：{nxt}" if nxt else " 下一次提醒：今天的提醒已全部到点")
    say(line)


def chat_loop(api_key, history, reminder, planning=False):
    """主循环：读一行输入，分派到「汇报 / 查询 / 规划 / 聊天 / 退出」。

    提醒由后台线程负责，这里始终阻塞在 input() 上，所以你随时都能打字。
    planning=True 表示今天还没有计划：先问一句「今天要做什么」，
    你回答的第一句会被拿去生成计划（汇报、查询这两个命令照常可用）。
    """
    if planning:
        prompt_plan()

    while True:
        try:
            line = input(PROMPT)
        except EOFError:
            say("")
            say("（输入已结束）再见，记得回来继续打卡学习！")
            return 0
        except KeyboardInterrupt:
            say("")
            say("再见，记得回来继续打卡学习！")
            return 0

        user_input = line.strip()
        if not user_input:
            continue
        if user_input.lower() in EXIT_COMMANDS:
            say("再见，记得回来继续打卡学习！")
            return 0

        # 每次开口都记一次时间差（跨进程持久化到 data/state.json）
        note_interaction()

        # 1) 「汇报 <内容>」：先记录（含两个时间差），再让模型接着点评一句
        report = split_report(user_input)
        if report is not None:
            if not report:
                say(f"{REPORT_COMMAND}后面要带上内容哦，例如：{REPORT_COMMAND} 今天读完了第 3 章")
                continue

            moment = CLOCK.now()
            slot = reminder.notify_report()
            due = slot_datetime(slot, moment) if slot else None
            delay = max(0.0, (moment - due).total_seconds()) if due else None
            gap = last_report_gap_seconds(moment)

            try:
                store.append_record(RECORDS_PATH, store.TYPE_REPORT, content=report, slot=slot,
                                    when=moment, gap_seconds=gap, delay_seconds=delay)
            except OSError as exc:
                say(f"[警告] 写入 {RECORDS_DISPLAY} 失败：{exc}")
            else:
                tag = f"对应 {slot} 提醒" if slot else "主动汇报"
                detail = []
                if delay is not None:
                    detail.append("迟 %s" % clock.format_duration(delay))
                if gap is not None:
                    detail.append("距上次汇报 %s" % clock.format_duration(gap))
                else:
                    detail.append("首次汇报")
                say(f"[已记录] {tag}（{'，'.join(detail)}）：{report}")

            if REPORT_TO_MODEL:
                prefix = f"【学习汇报，对应 {slot} 提醒】" if slot else "【学习汇报】"
                chat_once(history, api_key, prefix + report)
            continue

        # 2) 「查询」：看今天的记录
        if user_input == QUERY_COMMAND:
            show_today(reminder)
            continue

        # 3) 规划模式：这句话就是「今天要做什么」的回答，只问一次、一次成型
        if planning:
            plan = build_plan(user_input, api_key)
            if plan is None:
                say(f"（今天还没有计划，再说一次要做什么：{PLAN_ANSWER_HINT}）")
                continue
            show_plan(plan)
            say(" 规划完成，接下来正常聊天。")
            planning = False
            continue

        # 4) 其它输入：普通聊天
        chat_once(history, api_key, user_input)


def main():
    """命令行入口：装配定时提醒 + 今日规划 + 对话循环。"""
    use_utf8_output()

    loaded_names = load_env_file()
    api_key = read_api_key()
    if not api_key:
        print_config_hint()
        return 1

    key_from_env_file = any(name.upper() == API_KEY_ENV for name in loaded_names)
    key_source = ENV_FILE_NAME if key_from_env_file else f"环境变量 {API_KEY_ENV}"

    # 先用 NTP 校准系统时间：失败自动退回系统时钟，不影响启动
    CLOCK.sync()

    # 首次运行就把 data/records.json、data/plans.json 建好，方便你直接打开查看
    store.ensure_file(RECORDS_PATH)
    store.ensure_plans_file(PLANS_PATH)

    reminder = scheduler.ReminderScheduler(
        checkpoints=REMINDER_CHECKPOINTS,
        grace_minutes=GRACE_MINUTES,
        on_remind=show_reminder,
        on_missed=handle_missed,
        poll_seconds=REMINDER_POLL_SECONDS,
        clock=CLOCK.now,          # 调度与宽限判定都用校准后的时间
    )

    # 今天已经有计划就直接展示；没有就进入规划模式，先问你今天要做什么
    plan = today_plan()
    print_banner(key_source, reminder, plan is not None)
    if plan is not None:
        show_plan(plan)

    reminder.start()  # 守护线程：提醒照常触发，主线程照常等待输入

    history = []  # 完整对话历史，元素形如 {"role": "user"/"assistant", "content": "..."}
    try:
        return chat_loop(api_key, history, reminder, planning=plan is None)
    finally:
        reminder.stop()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        say("")
        say("再见，记得回来继续打卡学习！")
        sys.exit(0)

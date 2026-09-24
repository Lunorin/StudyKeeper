#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对话：把用户这句话交给大模型，拿回「回复文本 + 工具调用意图」。

边界（重要）：
- Python 不执行工具、不连数据库、不调 Java 接口，只把模型的意图原样交出去；
- history 由调用方（前端 / Java）传进来，这里不做会话持久化；流式与非流式共用同一份
  build_messages，所以两条路径的历史都会带给模型（不存在「流式由客户端自己维护历史」）；
- 一次请求最多返回一次 tool_calls，不做多轮自动工具调用；
- 流式版（chat_stream）只是把同一次对话边收边吐：内容一段段 delta 出去，
  工具调用攒齐后一次性给出；同样不执行工具、不落库、不缓存。

对外入口：
    chat(message, history, context) -> {"reply": str,
                                        "toolCalls": [{"name": str, "arguments": dict}, ...]}
    chat_stream(message, history, context) -> 逐条 yield 事件（delta / tool_calls / end），
                                       /ai/chat/stream 用；事件结构见函数注释

context 由调用方（Java 侧）现查后传进来，缺字段一律按空列表处理：
    todayTasks      今天已有的任务
    todayCourses    今天的课程表
    todayRestTimes  今天生效的休息时段
    userMemories    用户画像记忆（category + content），非空时注入 system prompt
    availableSlots  今天算好的可用空档（startTime + endTime），也注入 system prompt，
                    供「对话式排任务」参考；空档是 Java 侧算好的，这里不校验、不过滤

画像注入的边界：只是把「已经记住的几条」告诉模型，让它别每次都重新问一遍；
不在这里做排序 / 去重 / 评分，也不改工具调用的行为。userMemories 为空时不加这一段。

「对话式排任务」：用户问「我今天该怎么安排」「我想复习雅思怎么做」这类规划问题时，
模型先按 availableSlots 给出 2-5 条带具体时间段的建议，并问一句「要加吗」；用户同意后才调
add_task（每条建议调一次），要改就重新给一版完整建议再问一次（此时不调工具），用户拒绝则不调工具。
这些规则全部写在 SYSTEM_PROMPT 里，Python 侧不做任何配合逻辑：不解析建议、不校验建议是否落在
空档内、也不缓存「上一版建议」——上一版建议靠调用方通过 history 带回来。

system prompt 末尾还会带上「当前时间」（见 app/utils/time_context.py）：模型自己不知道现在几点，
不告诉它就容易把「今天 / 明天」算错。这一行每次请求都现取，不做缓存。
"""

import json

from app.services import llm_client
from app.services.llm_client import LLMError
from app.services.tools import TOOLS
from app.utils.time_context import now_text

FALLBACK_REPLY = "我没太理解，能再说一次吗？"
MAX_HISTORY = 50                 # 最多带 50 条历史消息，防止把上下文撑爆
MAX_TASKS_IN_CONTEXT = 200       # 今日任务最多列 200 条
MAX_COURSES_IN_CONTEXT = 50      # 今日课程最多列 50 条
MAX_REST_TIMES_IN_CONTEXT = 50   # 今日休息时段最多列 50 条
MAX_MEMORIES_IN_CONTEXT = 50     # 用户画像记忆最多列 50 条（Java 侧每类最多 3 条，实际 ≤ 15）
MAX_SLOTS_IN_CONTEXT = 50        # 今天可用空档最多列 50 条（一天排不出这么多，纯属兜底上限）

# 画像记忆的分类中文名；表里没有的类别（未知值）直接用英文原值展示
CATEGORY_LABELS = {
    "habit": "学习习惯",
    "emotion": "情绪状态",
    "event": "重要事件",
    "preference": "偏好",
    "goal": "长期目标",
}

UNKNOWN_CATEGORY = "其他"        # category 为空时的兜底文案

MEMORY_SECTION_TITLE = "你对该用户的了解："      # 追加在 system prompt 末尾那一段的标题
MEMORY_SECTION_HINT = ("在回复时可以自然参考这些信息，但不要直接罗列，"
                       "如果与当前对话无关不要硬扯。")

# 「可用空档」那一行：标题（含冒号）给注入用，规则里提到它时用不带冒号的名字
SLOTS_SECTION_TITLE = "今天可用空档："
SLOTS_SECTION_NAME = "今天可用空档"
EMPTY_SLOTS_TEXT = "（今天没有可用空档）"   # 一个空档都没有时的占位说明，SYSTEM_PROMPT 里会提到它

# 「对话式排任务」规则：规划类问题先给建议并问一句，等用户确认后才调 add_task。
# 单独成一段，方便对照规则本身；（name）（empty）由下面的常量填，改文案不用改两处。
PLANNING_RULES = (
    "【对话式排任务】\n"
    "当用户问「我今天该怎么安排」「我想复习 XX 怎么做」「给我点建议」这类规划问题时：\n"
    "1. 先分析可用空档（读 system 里的「{name}」那一行）；\n"
    "2. 给出 2-5 条具体建议，每条都写清「做什么」和「时间段（HH:mm-HH:mm）」；\n"
    "3. 所有建议的时段必须落在可用空档内；\n"
    "4. 最后问一句：「要我把这几项加到今天的任务里吗？」\n"
    "当用户回复同意词（「好的」「可以」「ok」「加吧」「嗯」）时：\n"
    "- 先回一句很短的确认（例如「好，这 3 项都加上啦～」，不超过 20 字），正文不能为空；\n"
    "- 再调 add_task 工具，每条建议调一次（有 3 条建议就调 3 次 add_task）；\n"
    "- 工具是必须的：只回文字说「已添加」而没调工具是不允许的（那句确认是工具之外的补充，不能替代工具）。\n"
    "当用户要修改某条建议时（如「我要 16:30-17:00 练听力」）：\n"
    "- 理解这是改上一次给的建议；\n"
    "- 保留其他建议不变；\n"
    "- 重新给出完整建议列表（含修改后的那条）；\n"
    "- 再问一次「要加吗」，此时不调工具，等用户确认。\n"
    "当用户说「算了」「不用」时：\n"
    "- 不调工具，正常回复。\n"
    "重要约束：\n"
    "- 只有用户明确同意（同意词或「加上吧」这类）时才调 add_task；其它问题（如「我今天有哪些任务」）正常回答、不要调工具；\n"
    "- 先给建议、等确认：用户确认前不要调 add_task，这条优先于上面「用户想新增任务就调工具」；\n"
    "- 「{name}」显示为「{empty}」时，不要编造时段，直接回复「今天好像没有空档时间了」；\n"
    "- 建议的时段必须精确到 HH:mm，不要说「下午」这种模糊说法；\n"
    "- 不要在建议里重复用户今天已有的任务。"
).format(name=SLOTS_SECTION_NAME, empty=EMPTY_SLOTS_TEXT)


SYSTEM_PROMPT = (
    "你是学习监督助手，说话温和、简短（1-3 句），像朋友一样鼓励用户。\n"
    "你能看到用户今天的任务列表、课程表、休息时段和可用空档（见下）。\n"
    "回答用户问题时可以参考这些信息：比如用户问「我几点有空」「什么时候背单词合适」，"
    "就结合课程表（上课时间）和休息时段来回答；用户提到上课 / 下课时间时，以课程表为准。\n"
    "课程表和休息时段是事实，不要编造里面没有的课或时段。\n"
    "如果用户想新增、修改或删除任务，请调用对应工具（add_task / update_task / delete_task），"
    "不要在正文里假装已经改好，也不要说「已为你修改」"
    "（只有下面【对话式排任务】里用户确认后，才允许在调工具的同时回一句很短的确认）。\n"
    "当用户表示已经完成了某项任务时（例如「我背完单词了」「数学作业做完了」），调用 complete_task 工具，"
    "从用户描述中提取任务关键词作为 keyword 参数；只要说「已帮你完成」而没调用工具是不对的。\n"
    "不要编造用户没提过的任务，也不要编造 keyword：用户没说清是哪一项（如只说「我学完了」）时不要调用工具，"
    "先问一句是哪一项；complete_task 只用 keyword、不传 taskId；"
    "修改或删除时，taskId 只能用任务列表里出现过的 id。\n"
    "如果用户只是聊天或提问，直接回复就好，不要调用工具；只有「某项任务做完了」才用 complete_task。\n"
) + PLANNING_RULES


def format_tasks(today_tasks):
    """把今日任务排成给模型看的多行文本；没有任务就给一句话说明。"""
    if not today_tasks:
        return "（今天还没有任务）"

    lines = []
    for task in today_tasks[:MAX_TASKS_IN_CONTEXT]:
        lines.append("#{id} [{status}] {title}（{start} - {end}）".format(
            id=task.get("id"),
            status=task.get("status"),
            title=task.get("title"),
            start=task.get("startTime"),
            end=task.get("endTime"),
        ))
    return "\n".join(lines)


def format_courses(today_courses):
    """把今天的课排成给模型看的多行文本；没有课就给一句话说明。"""
    if not today_courses:
        return "（今天没有课）"

    lines = []
    for course in today_courses[:MAX_COURSES_IN_CONTEXT]:
        lines.append("{name}（{start} - {end}）".format(
            name=course.get("courseName"),
            start=course.get("startTime"),
            end=course.get("endTime"),
        ))
    return "\n".join(lines)


def format_rest_times(today_rest_times):
    """把今天的休息时段排成给模型看的多行文本；没有就给一句话说明。"""
    if not today_rest_times:
        return "（今天没有固定的休息时段）"

    lines = []
    for rest_time in today_rest_times[:MAX_REST_TIMES_IN_CONTEXT]:
        window = "{start} - {end}".format(
            start=rest_time.get("startTime"),
            end=rest_time.get("endTime"),
        )
        label = str(rest_time.get("label") or "").strip()
        if label:
            lines.append("{window}（{label}）".format(window=window, label=label))
        else:
            lines.append(window)
    return "\n".join(lines)


def format_slots(available_slots):
    """把今天的可用空档拼成给模型看的一行「12:00-14:10、15:40-19:10」；一条都没有就给占位说明。

    空档是 Java 侧算好的，这里只负责展示，不做时间校验、也不和课程/任务比对：
    起止时间只要都不是空串就照原样拼出来，缺一个的条目直接跳过（宁可不展示，也别给模型半个时段）。
    返回 EMPTY_SLOTS_TEXT 时，模型会按 SYSTEM_PROMPT 的约定回复「今天好像没有空档时间了」。
    """
    windows = []
    for slot in (available_slots or [])[:MAX_SLOTS_IN_CONTEXT]:
        if not isinstance(slot, dict):
            continue
        start = str(slot.get("startTime") or "").strip()
        end = str(slot.get("endTime") or "").strip()
        if not start or not end:
            continue
        windows.append("{}-{}".format(start, end))

    if not windows:
        return EMPTY_SLOTS_TEXT
    return "、".join(windows)


def format_user_memories(user_memories):
    """把用户画像记忆排成给模型看的多行文本，每条一行「- [分类] 内容」。

    分类用中文名（habit → 学习习惯 …）；表里没有的类别直接用英文原值，
    category 为空的用「其他」兜底。内容为空、不是对象的条目直接跳过。
    一条都没有时返回空串 —— 调用方据此决定「不加这一段」。
    """
    lines = []
    for item in (user_memories or [])[:MAX_MEMORIES_IN_CONTEXT]:
        if not isinstance(item, dict):
            continue
        # 收成一行，避免多行内容把「每条一行」的格式冲散
        content = " ".join(str(item.get("content") or "").split())
        if not content:
            continue
        category = str(item.get("category") or "").strip()
        label = CATEGORY_LABELS.get(category) or category or UNKNOWN_CATEGORY
        lines.append("- [{}] {}".format(label, content))
    return "\n".join(lines)


def build_messages(message, history, context):
    """组装 messages：system（角色设定 + 今日任务 / 课程表 / 休息时段 / 可用空档
    + 用户画像记忆 + 当前时间）→ history 原样带上 → 当前这句。"""
    today_tasks = (context or {}).get("todayTasks") or []
    today_courses = (context or {}).get("todayCourses") or []
    today_rest_times = (context or {}).get("todayRestTimes") or []
    available_slots = (context or {}).get("availableSlots") or []
    memories = format_user_memories((context or {}).get("userMemories") or [])
    system_content = (
        "{prompt}\n\n"
        "用户今天的任务列表：\n{tasks}\n\n"
        "用户今天的课程表：\n{courses}\n\n"
        "用户今天的休息时段：\n{rests}\n\n"
        "{slots_title}{slots}"
    ).format(
        prompt=SYSTEM_PROMPT,
        tasks=format_tasks(today_tasks),
        courses=format_courses(today_courses),
        rests=format_rest_times(today_rest_times),
        slots_title=SLOTS_SECTION_TITLE,
        slots=format_slots(available_slots),
    )

    # 有画像记忆才追加这一段；没有画像时这里什么都不加，不会留下空标题
    if memories:
        system_content = "{}\n\n{}\n{}\n\n{}".format(
            system_content, MEMORY_SECTION_TITLE, memories, MEMORY_SECTION_HINT)

    # 当前时间放在最后一行：每次请求都现取（服务可能连续跑很多天，缓存下来的时间会过时）
    system_content = "{}\n\n当前时间：{}".format(system_content, now_text())

    messages = [{"role": "system", "content": system_content}]

    for item in (history or [])[:MAX_HISTORY]:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "").strip() or "user"
        messages.append({"role": role, "content": item.get("content") or ""})

    messages.append({"role": "user", "content": message})
    return messages


def parse_arguments(tool_name, raw_arguments):
    """把工具参数解析成 dict：模型给的是一段 JSON 字符串。

    空 / 没有则当 {}；解析失败或不是 JSON 对象就抛 LLMError（宁可明确失败，
    也别把残缺参数交给 Java）。流式与非流式共用这一份逻辑，保证两条路径行为一致。
    """
    if raw_arguments is None or raw_arguments == "":
        return {}
    try:
        arguments = json.loads(raw_arguments)
    except ValueError as exc:
        raise LLMError("工具 {} 的参数不是合法 JSON：{}".format(tool_name, exc)) from exc
    if not isinstance(arguments, dict):
        raise LLMError("工具 {} 的参数必须是 JSON 对象".format(tool_name))
    return arguments


def parse_tool_calls(raw_tool_calls):
    """把 SDK 的 tool_calls 转成 [{"name": str, "arguments": dict}]（非流式用）。

    arguments 是模型给的 JSON 字符串。解析失败说明模型没按 schema 来，
    直接当调用失败抛出：否则 Java 侧拿到残缺参数更难排查。
    """
    calls = []
    for item in raw_tool_calls or []:
        function = getattr(item, "function", None)
        name = getattr(function, "name", None)
        if not name:
            continue

        calls.append({"name": name,
                      "arguments": parse_arguments(name, getattr(function, "arguments", None))})
    return calls


def chat(message, history, context):
    """对外入口：调模型 → 拆出 reply 与 toolCalls；两者都空时给一句兜底回复。"""
    messages = build_messages(message, history, context)
    result = llm_client.chat_with_tools(messages, TOOLS)

    reply = (result.content or "").strip()
    tool_calls = parse_tool_calls(result.tool_calls)
    if not reply and not tool_calls:
        reply = FALLBACK_REPLY

    return {"reply": reply, "toolCalls": tool_calls}


# 流式事件类型（chat_stream yield 的 dict 里 type 字段的取值）
EVENT_DELTA = "delta"                # 一小段正文
EVENT_TOOL_CALLS = "tool_calls"      # 攒齐的工具调用意图
EVENT_END = "end"                    # 本轮结束（带完整回复）


class ToolCallAccumulator:
    """把流式返回的 tool_calls 片段按 index 拼成完整调用。

    流式下模型不会一次给完整调用，而是拆成很多片：
    - id / function.name 一般只在第一片出现；
    - function.arguments 是一段段追加的 JSON 字符串（拼起来才是合法 JSON）；
    - 同一轮里可能有多个工具调用，用 index（0、1、…）区分。

    拼出来的结构与非流式的 parse_tool_calls 完全一致：[{"name": str, "arguments": dict}]，
    这样 Java 侧两条路径可以走同一套执行逻辑。个别服务端不给 index，就并到最近用过的那个。
    """

    def __init__(self):
        self._parts = {}          # index -> {"id": str, "name": str, "arguments": str}
        self._last_index = 0

    def add(self, delta_tool_calls):
        """把一小片 tool_calls 并进来（来自 chunk.choices[0].delta.tool_calls）。"""
        for item in delta_tool_calls or []:
            index = getattr(item, "index", None)
            if not isinstance(index, int):
                index = self._last_index
            self._last_index = index

            part = self._parts.setdefault(index, {"id": None, "name": None, "arguments": ""})

            call_id = getattr(item, "id", None)
            if call_id:
                part["id"] = call_id

            function = getattr(item, "function", None)
            name = getattr(function, "name", None)
            if name:
                part["name"] = name

            arguments = getattr(function, "arguments", None)
            if arguments:
                part["arguments"] += arguments

    def build(self):
        """拼装完成：按 index 升序输出（没拿到名字的片段丢掉，与 parse_tool_calls 一致）。"""
        calls = []
        for index in sorted(self._parts):
            part = self._parts[index]
            if not part["name"]:
                continue
            calls.append({"name": part["name"],
                          "arguments": parse_arguments(part["name"], part["arguments"])})
        return calls


def first_delta(chunk):
    """取一个 chunk 里的第一个 delta；没有候选项（如只带 usage 的收尾块）返回 None。"""
    choices = getattr(chunk, "choices", None)
    if not choices:
        return None
    return getattr(choices[0], "delta", None)


def chat_stream(message, history, context):
    """对外入口（流式）：逐条 yield 事件，由 /ai/chat/stream 转成 SSE。

    yield 的三种事件：
        {"type": "delta", "content": "…"}   模型刚吐出来的一小段正文（原样，不 trim）
        {"type": "tool_calls", "toolCalls": [{"name": str, "arguments": dict}, ...]}
                                            工具调用攒齐后一次性给出（没有就不发这个事件）
        {"type": "end", "reply": "…"}       本轮结束，reply 是完整回复（已 trim）

    与非流式 chat() 的差别：
    - 参数与 chat() 完全一样（message / history / context），messages 也共用 build_messages：
      历史同样会带给模型 —— 否则模型看不到自己上一轮的建议，「好的」这种回答就无从对应；
    - 模型整轮什么都没说、也没想调工具时，补一句兜底文案（与 chat() 一致），
      并且先 delta 再 end，保证「客户端拼出来的文字」与 end.reply 一致；
    - 工具参数不是合法 JSON 时抛 LLMError，由路由层转成 error 事件 ——
      此时前面的 delta 已经发出去了，这是流式固有的特性（不做回撤）。
    """
    messages = build_messages(message, history, context)
    accumulator = ToolCallAccumulator()
    parts = []

    for chunk in llm_client.chat_with_tools_stream(messages, TOOLS):
        delta = first_delta(chunk)
        if delta is None:
            continue

        content = getattr(delta, "content", None)
        if content:
            parts.append(content)
            yield {"type": EVENT_DELTA, "content": content}

        accumulator.add(getattr(delta, "tool_calls", None))

    tool_calls = accumulator.build()
    if tool_calls:
        yield {"type": EVENT_TOOL_CALLS, "toolCalls": tool_calls}

    reply = "".join(parts).strip()
    if not reply and not tool_calls:
        # 与 chat() 一致：模型什么都没说时别让客户端显示一个空气泡
        reply = FALLBACK_REPLY
        yield {"type": EVENT_DELTA, "content": FALLBACK_REPLY}

    yield {"type": EVENT_END, "reply": reply}

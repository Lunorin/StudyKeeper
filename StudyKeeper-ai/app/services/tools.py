#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""工具（Function Calling）schema：告诉模型「用户想改任务时能调哪些函数」。

Python 这边只定义 schema、只把模型的调用意图原样返回给调用方；
真正的执行（写库 / 改 Java 侧数据）由 Java 完成，这里不实现任何执行逻辑。

导出：TOOLS —— OpenAI 格式的工具列表，直接传给 chat.completions.create(tools=...)，
用法见 app/services/llm_client.chat_with_tools()。

四个工具：
    add_task      添加一条今日任务（title 必填）
    update_task   修改一条已有任务（taskId 必填，其余按需给）
    delete_task   删除一条任务（taskId 必填）
    complete_task 标记今日任务已完成（keyword 必填：用户口中的任务关键词，
                  怎么把它匹配到今日任务是调用方的事）
"""

HOUR_HINT = "24 小时制 HH:mm，例如 19:30"
PRIORITY_HINT = "优先级：high / medium / low"

ADD_TASK = {
    "type": "function",
    "function": {
        "name": "add_task",
        "description": "添加一条今日任务",
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "任务标题，一句话说清做什么"},
                "startTime": {"type": "string", "description": "开始时间，" + HOUR_HINT},
                "endTime": {"type": "string", "description": "结束时间，" + HOUR_HINT},
                "priority": {"type": "string", "enum": ["high", "medium", "low"],
                             "description": PRIORITY_HINT},
            },
            "required": ["title"],
        },
    },
}

UPDATE_TASK = {
    "type": "function",
    "function": {
        "name": "update_task",
        "description": "修改一条已有任务",
        "parameters": {
            "type": "object",
            "properties": {
                "taskId": {"type": "integer", "description": "要修改的任务 id，取自今日任务列表"},
                "title": {"type": "string", "description": "新的任务标题"},
                "startTime": {"type": "string", "description": "新的开始时间，" + HOUR_HINT},
                "endTime": {"type": "string", "description": "新的结束时间，" + HOUR_HINT},
                "priority": {"type": "string", "enum": ["high", "medium", "low"],
                             "description": PRIORITY_HINT},
            },
            "required": ["taskId"],
        },
    },
}

DELETE_TASK = {
    "type": "function",
    "function": {
        "name": "delete_task",
        "description": "删除一条任务",
        "parameters": {
            "type": "object",
            "properties": {
                "taskId": {"type": "integer", "description": "要删除的任务 id，取自今日任务列表"},
            },
            "required": ["taskId"],
        },
    },
}

COMPLETE_TASK = {
    "type": "function",
    "function": {
        "name": "complete_task",
        "description": "标记今日任务为已完成。当用户表示已经完成了某项任务时调用"
                       "（例如：我背完单词了 / 数学作业写完了 / 错题整理好了）",
        "parameters": {
            "type": "object",
            "properties": {
                "keyword": {"type": "string",
                            "description": "任务的关键词，从用户描述中提取，"
                                           "例如'单词'、'数学'、'错题'、'听力'"},
            },
            "required": ["keyword"],
        },
    },
}

TOOLS = [ADD_TASK, UPDATE_TASK, DELETE_TASK, COMPLETE_TASK]

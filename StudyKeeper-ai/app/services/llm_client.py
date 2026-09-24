#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DeepSeek 调用封装（openai SDK，OpenAI 兼容接口）。

三个出口，都只负责「发消息 → 拿回结果」，不做业务解析：
- chat_json(messages)            强制 JSON 输出，返回 JSON 文本（planner 用）；
- chat_with_tools(messages, tools) 带工具调用能力，返回模型的 message 对象
  （含 content 和 tool_calls，chat 用）；
- chat_with_tools_stream(messages, tools) 同上但 stream=True，逐块 yield 原始 chunk
  （/ai/chat/stream 用；delta 怎么拼是 chat.py 的事，这里不解析）。

约定：
- key 从 app.config.get_settings() 读，日志里任何地方都不打印它；
- chat_json 会带上 response_format={"type": "json_object"}。DeepSeek 官方要求开启
  JSON 输出时提示词里必须出现 "json" 字样并给出格式示例（planner 的 system prompt
  已满足），同时 max_tokens 要给足，否则 JSON 会被截断成非法格式；
- JSON 输出偶尔会返回空内容，chat_json 把它当失败抛出，由调用方决定是否重试；
- chat_with_tools 不强制 JSON、不设置 tool_choice（默认 auto：闲聊时不会调工具），
  也不会自动执行工具——执行是 Java 侧的事；
- chat_with_tools_stream 一次性拿到流对象后逐块吐给调用方，不做任何解析、不缓存内容，
  连接中途断开 / 超时统一抛 LLMError（可能在迭代过程中抛，调用方要包住 for 循环）；
- SDK 的原始异常统一收成 LLMError，让上层只处理一种错误类型。
"""

from openai import OpenAI, OpenAIError

from app.config import get_settings

BASE_URL = "https://api.deepseek.com/v1"
# 原先的 deepseek-chat 已不在 DeepSeek 现行模型列表里（2026-09 实测只剩
# deepseek-flash / deepseek-v4-pro），这里用实际可用的 deepseek-flash。
MODEL = "deepseek-flash"
TIMEOUT_SECONDS = 60.0          # 单次请求超时；SDK 默认还会重试 2 次
TEMPERATURE = 0.3               # 规划类任务要稳，别发散
MAX_TOKENS = 2048               # 给足，避免 tasks 被截断
JSON_RESPONSE_FORMAT = {"type": "json_object"}

_client = None                  # 复用的 OpenAI 客户端
_client_api_key = None          # 建客户端时用的 key，变了就重建


class LLMError(RuntimeError):
    """大模型调用失败：缺 key / 网络 / 鉴权 / 限流 / 超时 / 空返回等。"""


def get_client():
    """按当前 key 返回（并复用）OpenAI 客户端；没有 key 直接抛 LLMError。"""
    global _client, _client_api_key

    settings = get_settings()
    if not settings.deepseek_api_key:
        raise LLMError("没有读到 DEEPSEEK_API_KEY，请检查项目根目录的 .env")

    if _client is None or _client_api_key != settings.deepseek_api_key:
        _client = OpenAI(
            api_key=settings.deepseek_api_key,
            base_url=BASE_URL,
            timeout=TIMEOUT_SECONDS,
        )
        _client_api_key = settings.deepseek_api_key
    return _client


def chat_json(messages, max_tokens=None):
    """让模型按 JSON 格式回答，返回回答文本（str）；失败抛 LLMError。

    messages   用标准 OpenAI 格式：[{"role": "system", "content": "..."}, ...]
    max_tokens 不传就用默认的 MAX_TOKENS；课程解析这类输出可能较长的场景可以调大，
               避免 JSON 被截断成非法格式。
    """
    if not isinstance(messages, list) or not messages:
        raise LLMError("messages 不能为空")

    client = get_client()
    try:
        response = client.chat.completions.create(
            model=MODEL,
            messages=messages,
            response_format=JSON_RESPONSE_FORMAT,
            temperature=TEMPERATURE,
            max_tokens=max_tokens or MAX_TOKENS,
            stream=False,
        )
    except OpenAIError as exc:          # SDK 异常体系：鉴权、限流、超时、5xx…
        raise LLMError("DeepSeek 调用失败：{}".format(exc)) from exc
    except Exception as exc:            # 兜底：断网、证书等非 SDK 异常
        raise LLMError("DeepSeek 调用异常：{}".format(exc)) from exc

    content = ""
    if response.choices:
        content = (response.choices[0].message.content or "")

    content = content.strip()
    if not content:
        raise LLMError("DeepSeek 返回了空内容（JSON 输出偶发情况，可重试）")
    return content


def chat_with_tools(messages, tools):
    """带工具（Function Calling）的对话，返回模型的 message 对象。

    与 chat_json 的区别：
    - 不传 response_format，模型可以自由用自然语言回复；
    - 多传一个 tools（OpenAI 格式的工具 schema，见 app/services/tools.py）；
    - 不设置 tool_choice，用默认的 auto：闲聊时模型不调工具，用户想改任务时才调；
    - 返回的是 SDK 的 message 对象，不是文本：message.content（可能为 None）、
      message.tool_calls（可能为 None；元素形如 ToolCall(id=..., function=Function(
      name="add_task", arguments='{"title": "..."}'))）。
    这里不执行任何工具，也不做多轮自动调用——执行由 Java 侧负责。

    失败（缺 key / 网络 / 鉴权 / 限流 / 无候选项）统一抛 LLMError。
    """
    if not isinstance(messages, list) or not messages:
        raise LLMError("messages 不能为空")
    if not isinstance(tools, list) or not tools:
        raise LLMError("tools 不能为空")

    client = get_client()
    try:
        response = client.chat.completions.create(
            model=MODEL,
            messages=messages,
            tools=tools,
            temperature=TEMPERATURE,
            max_tokens=MAX_TOKENS,
            stream=False,
        )
    except OpenAIError as exc:          # SDK 异常体系：鉴权、限流、超时、5xx…
        raise LLMError("DeepSeek 调用失败：{}".format(exc)) from exc
    except Exception as exc:            # 兜底：断网、证书等非 SDK 异常
        raise LLMError("DeepSeek 调用异常：{}".format(exc)) from exc

    if not response.choices:
        raise LLMError("DeepSeek 没有返回任何候选项")

    message = response.choices[0].message
    if message is None:
        raise LLMError("DeepSeek 没有返回 message")
    return message


def chat_with_tools_stream(messages, tools):
    """带工具的流式对话：逐块 yield SDK 的 chunk 对象（不做任何解析）。

    与 chat_with_tools 的区别只有一处：多传 stream=True，返回的是迭代器而不是完整 message。
    调用方（chat.chat_stream）要自己遍历 chunk.choices[0].delta，把 content 与 tool_calls
    的片段拼起来 —— 拼接逻辑属于业务，不放在这里。

    这是生成器函数，所以「取客户端」发生在第一次迭代时：没配 key / 连不上时，
    LLMError 会在 for 循环里抛出，调用方一定要把循环包在 try 里。

    失败（缺 key / 网络 / 鉴权 / 限流 / 连接中途断开）统一抛 LLMError。
    """
    if not isinstance(messages, list) or not messages:
        raise LLMError("messages 不能为空")
    if not isinstance(tools, list) or not tools:
        raise LLMError("tools 不能为空")

    client = get_client()
    try:
        stream = client.chat.completions.create(
            model=MODEL,
            messages=messages,
            tools=tools,
            temperature=TEMPERATURE,
            max_tokens=MAX_TOKENS,
            stream=True,           # 唯一的区别：要流式
        )
    except OpenAIError as exc:          # SDK 异常体系：鉴权、限流、超时、5xx…
        raise LLMError("DeepSeek 流式调用失败：{}".format(exc)) from exc
    except Exception as exc:            # 兜底：断网、证书等非 SDK 异常
        raise LLMError("DeepSeek 流式调用异常：{}".format(exc)) from exc

    # 迭代过程中也可能断（超时 / 网络抖动），这里再包一层，让调用方只处理 LLMError
    try:
        for chunk in stream:
            yield chunk
    except OpenAIError as exc:
        raise LLMError("DeepSeek 流式中断：{}".format(exc)) from exc
    except Exception as exc:
        raise LLMError("DeepSeek 流式异常：{}".format(exc)) from exc

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""应用配置：用 python-dotenv 读取项目根目录的 .env，暴露 Settings。

7.0 阶段只读一个变量：
    DEEPSEEK_API_KEY    DeepSeek 的 API Key，7.1 接入大模型时才会真正用到

约定：
- .env 位于项目根目录（本文件的上一级），路径不写死，跟着项目走；
- 读取用 override=False，已存在的系统环境变量优先，不会被 .env 覆盖；
- 缺少 DEEPSEEK_API_KEY 时只往 stderr 打警告，不抛异常、不退出，
  这样骨架阶段没 key 也能正常起服务；
- Settings 里存的是 key 原文，任何日志都不要把它打出来。
"""

import os
import sys
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent          # <项目根>
ENV_FILE = BASE_DIR / ".env"                               # 默认读取的 .env
ENV_FILE_NAME = ".env"                                     # 仅用于提示文案
API_KEY_ENV = "DEEPSEEK_API_KEY"                           # 目前唯一读取的变量

_settings = None                                           # 单例缓存


@dataclass(frozen=True)
class Settings:
    """运行期配置。字段暂时只有 DeepSeek 的 key。"""

    deepseek_api_key: str      # 读到就是真 key，读不到是空字符串
    env_file: str              # .env 的绝对路径（排错用，不含 key 内容）
    api_key_loaded: bool       # 是否真的读到了非空 key


def load_env(path=None):
    """把 .env 读进 os.environ，返回是否找到了文件。

    参数 path 留成可选，是为了能在不碰真实 .env 的前提下单独验证
    「文件不存在」这条分支。override=False：已有环境变量优先，不被 .env 覆盖。
    """
    env_path = Path(path) if path is not None else ENV_FILE
    if not env_path.is_file():
        print("[警告] 没找到 {}（{}），将只使用系统环境变量。".format(
            ENV_FILE_NAME, env_path), file=sys.stderr)
        return False
    load_dotenv(dotenv_path=str(env_path), override=False)
    return True


def get_settings(reload=False):
    """返回全局唯一的 Settings；reload=True 时重新读取一次。"""
    global _settings
    if _settings is None or reload:
        _settings = _build_settings()
    return _settings


def _build_settings():
    """读 .env + 系统环境变量，组装 Settings（缺 key 只警告）。"""
    load_env()

    api_key = (os.getenv(API_KEY_ENV) or "").strip()
    if not api_key:
        print("[警告] 环境变量 {} 为空，{} 里也没读到它；服务照常启动，"
              "但接入大模型（7.1）时会失败。".format(API_KEY_ENV, ENV_FILE_NAME),
              file=sys.stderr)

    return Settings(
        deepseek_api_key=api_key,
        env_file=str(ENV_FILE),
        api_key_loaded=bool(api_key),
    )

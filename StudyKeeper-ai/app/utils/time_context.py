#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""当前时间文本：给模型看的「现在是几点」。

大模型本身没有时间概念，不告诉它现在几点，它就会把「今天 / 明天 / 还有几天」算错。
本模块只负责生成这一行文本，注入 system prompt 由各 service 自己做：

    chat.build_messages     → /ai/chat
    planner.build_messages  → /ai/plan

约定：
- 用本机本地时间（datetime.now()），不做时区配置，也不读环境变量；
- 形如 "2026-09-19 15:30（星期六）"：日期 yyyy-MM-dd、时间 HH:mm（不带秒）、星期用中文；
- 每次调用都重新取时间，调用方不要把它存成模块常量 —— 服务可能连续跑很多天，
  常量化的时间会一直停在启动那一刻。
"""

from datetime import datetime

TIME_FORMAT = "%Y-%m-%d %H:%M"

# 下标与 datetime.weekday() 对齐：0=周一 … 6=周日
WEEKDAY_LABELS = ("一", "二", "三", "四", "五", "六", "日")


def now_text():
    """返回当前本地时间，形如 "2026-09-19 15:30（星期六）"。

    每次调用都重新取时间（调用方不要缓存结果）。
    """
    now = datetime.now()
    return "{}（星期{}）".format(now.strftime(TIME_FORMAT), WEEKDAY_LABELS[now.weekday()])

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通用小工具包：与具体业务无关、被多个 service 复用的纯函数。

- time_context : 当前时间文本（给模型看的「现在几点」）。

分层约定：utils 不 import services / routers / config，只提供最底层的纯函数，
避免出现反向依赖。
"""

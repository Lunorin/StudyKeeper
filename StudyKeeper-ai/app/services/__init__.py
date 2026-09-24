#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务层：与「大模型」和「业务规则」相关的纯 Python 模块。

- llm_client : 只管发请求 / 收响应，不掺业务；
- planner    : 只管提示词与数据校验，不依赖 FastAPI / Pydantic，可单独调用。

分层约定：routers（HTTP 与参数校验）→ services（业务）→ config（配置），
services 里不 import routers，避免循环依赖。
"""

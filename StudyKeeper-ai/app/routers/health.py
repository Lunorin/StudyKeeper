#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""健康检查路由：GET /health。

只回答「进程活着吗」，不检查 .env、不探测大模型，所以永远不依赖外部服务。
"""

from fastapi import APIRouter

SERVICE_NAME = "study-agent-ai"     # 服务标识，客户端据此确认连对了后端

router = APIRouter(tags=["health"])


@router.get("/health", summary="健康检查")
def health_check():
    """返回服务状态；字段固定为 status / service。"""
    return {"status": "ok", "service": SERVICE_NAME}

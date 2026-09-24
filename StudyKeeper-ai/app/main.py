#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""StudyKeeper AI 服务入口：创建 FastAPI 应用并注册路由。

启动方式（在项目根目录）：
    .venv\\Scripts\\python.exe -m uvicorn app.main:app --port 8000
    .venv\\Scripts\\python.exe -m app.main          （等价，便于本地调试）

注意：根目录另有一个 main.py，那是第 1~3 版的命令行版 StudyKeeper，
两者互不影响——这里用的是 app.main:app（包内模块），不会撞名。
"""

from fastapi import FastAPI

from app.config import get_settings
from app.routers import ai, health

SERVICE_TITLE = "StudyKeeper AI Service"
SERVICE_VERSION = "0.1.3"
SERVICE_DESCRIPTION = ("学习监督助手后端（7.3：/health + /ai/plan 任务拆解 + "
                       "/ai/chat 对话与工具调用意图 + /ai/parse-course 课程解析）")

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8000
RELOAD_IN_DEBUG = True                 # 直接 python -m app.main 时才开热重载

app = FastAPI(
    title=SERVICE_TITLE,
    version=SERVICE_VERSION,
    description=SERVICE_DESCRIPTION,
)

# 注册路由：都不带 prefix 之外的额外前缀，所以是 /health、/ai/plan
app.include_router(health.router)
app.include_router(ai.router)

# 启动即读一次 .env：缺 DEEPSEEK_API_KEY 时只在控制台警告，不影响服务启动
SETTINGS = get_settings()


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host=DEFAULT_HOST, port=DEFAULT_PORT,
                reload=RELOAD_IN_DEBUG)

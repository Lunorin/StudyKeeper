# ============================================================================
# Python AI 服务镜像（FastAPI + uvicorn）
#
# 构建上下文 = StudyKeeper-ai/（同目录 .dockerignore 已排除 .venv/、__pycache__/、.env）
# DEEPSEEK_API_KEY 由 docker compose 通过 env_file 传进来，**不打进镜像**。
# 本地 Python 是 3.14（.venv 里是 cpython-314），所以这里用 3.14-slim；换成 3.13-slim 也能跑。
# ============================================================================

FROM python:3.14-slim
WORKDIR /app

# 不写 pip 缓存、日志不缓冲（docker logs 能实时看到）、不生成 .pyc
ENV PIP_NO_CACHE_DIR=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TZ=Asia/Shanghai

# 依赖单独一层：只改 app/ 里的代码不会触发重新 pip install
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# 只拷包目录。根目录那些第 1~3 版的命令行脚本（main.py / planner.py / store.py / clock.py）
# 不进镜像，运行时用不到。
COPY app/ app/

EXPOSE 8000

# 必须监听 0.0.0.0：容器里 127.0.0.1 只有自己，同网络的 backend 就连不上了
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

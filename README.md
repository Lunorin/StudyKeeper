# StudyKeeper · 学习监督 Agent

> 一个能读懂你课表的 AI 学习助手。

[在线体验](http://8.163.81.8) · [接口文档](docs/api.md) · [架构设计](docs/architecture.md)

![今日任务](docs/screenshots/today.png)

## 是什么

学生知道要学习，但不知道从哪开始，坚持不下来。

StudyKeeper 用 AI 解决这个问题：

- **主动规划**：读你的课表，把目标拆成可执行的任务
- **记住你**：积累学习习惯，越用越懂你
- **监督你**：提醒、统计、反馈

## 功能

- **AI 任务规划** — 输入目标，AI 避开课程时段自动排任务
- **对话式管理** — 说「我背完单词了」，AI 自动标记完成
- **用户画像** —  从对话提炼习惯、情绪、偏好，下次对话参考
- **流式输出** — 逐字返回，体验接近 ChatGPT
- **长期任务** — 按周几自动生成，支持懒加载

## 架构
Vue3 前端 → Spring Boot 后端 → Python AI 服务 → DeepSeekAPI


- **Java 管业务**：用户、任务、课表、统计、认证
- **Python 管 AI**：对话、拆解、提炼
- **大模型只输出结构化结果**：不直接操作数据库

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue3 + Vite + Element Plus |
| 后端 | Spring Boot 3 + MyBatis-Plus + MySQL |
| AI | Python + FastAPI + OpenAI SDK |
| 模型 | DeepSeek |
| 部署 | Docker Compose + Nginx |

## 技术亮点

- **工具调用**：AI 返回 `add_task` / `complete_task` 等工具调用，Java 执行并控制事务
- **流式输出**：SSE 三层转发（Python → Java → 前端）
- **用户画像**：异步提炼 + 分类存储 + 按需注入 system prompt
- **约束感知规划**：Java 算可用时段，Python 二次校验，任务永不冲突课程

## 快速开始

```bash
git clone https://github.com/Lunorin/StudyKeeper.git
cd StudyKeeper
cp .env.example .env
vim .env                    # 填 DeepSeek API Key、数据库密码
docker compose up -d

访问 http://localhost

项目结构
StudyKeeper/
├── StudyKeeper/              # Spring Boot 后端
├── StudyKeeper-ai/           # Python AI 服务
├── study-agent-frontend/     # Vue3 前端
├── nginx/                    # Nginx 配置
└── docker-compose.yml

Roadmap
☑ 任务管理 + AI 规划
☑ 对话式管理 + 用户画像
☑ 流式输出 + 邮箱注册
☑ Docker 部署
□ 课程表图片识别
□ 定时主动提醒

⭐ 如果对你有帮助，欢迎 Star

# ============================================================================
# 前端镜像：Vite 构建 → nginx 提供静态文件
#
# 方案取舍（A 还是 B）：
#   本项目选 **方案 B 的思路**——不单独跑一个「前端 nginx」再让主 nginx 反代过去。
#   实现上更省一步：这个镜像自带 nginx + 构建好的 dist，compose 里的 nginx 服务就是它，
#   再把仓库根的 nginx/nginx.conf 挂进 /etc/nginx/conf.d/default.conf 来配「静态文件 + /api 反代」。
#   为什么不用「前端只产 dist，主 nginx 挂宿主目录」那种做法：那样得先手工构建一次
#   （Windows 下还得处理路径挂载），漏构建就会线上跑旧代码；放进镜像里，`docker compose up --build` 一步到位。
# ============================================================================

# ---------- stage 1：构建 ----------
FROM node:20-alpine AS build
WORKDIR /app

# Vite 8 要求 Node ^20.19.0 || >=22.12.0，node:20-alpine 满足（低于这个版本会在构建时报 engine 错误）
# 先装依赖再拷源码：只改业务代码时这一层能命中缓存
COPY package.json package-lock.json ./
# package-lock 与 package.json 不一致时 npm ci 会直接失败，所以退回 npm install 兜底
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

COPY . .
RUN npm run build

# ---------- stage 2：运行 ----------
FROM nginx:alpine

# 站点配置由 compose 挂载仓库根的 nginx/nginx.conf 提供（直接 docker run 这个镜像时用的是官方默认配置）
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

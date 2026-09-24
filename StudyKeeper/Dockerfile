# ============================================================================
# Java 后端镜像：多阶段构建
#   stage 1（maven）：mvn package 打出可执行 jar
#   stage 2（jre）  ：只把 jar 拿过来，镜像里没有源码、没有 maven、没有依赖缓存
#
# 构建上下文 = StudyKeeper/（外面的 .dockerignore 排除了 target/、.idea/ 等）
# ============================================================================

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先把 pom.xml 单独拷进来把依赖拉下来：这样「只改业务代码」时这一层还能命中缓存，
# 不用每次重新下几百 MB 的 maven 依赖。
# （末尾的 || true：go-offline 偶尔会因为个别插件解析不了而失败，失败也无所谓 —— 下一步 package 才是真的校验）
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline || true

# 再拷源码打包。
# -DskipTests 是必须的：StudyKeeperApplicationTests 是 @SpringBootTest，需要连真 MySQL，
# 而镜像构建阶段没有数据库。要跑测试请在本地 `mvn test`。
COPY src ./src
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------------------

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 时区：后端算「今天」用的是 LocalDateTime.now()，容器里默认是 UTC 会差 8 小时
ENV TZ=Asia/Shanghai

# Spring Boot 打出来的 jar 带版本号，这里统一改名 app.jar，启动命令就不用跟着版本走
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# 不写死堆大小：交给容器内存（要限制就通过 JAVA_TOOL_OPTIONS 环境变量传）
ENTRYPOINT ["java", "-jar", "app.jar"]

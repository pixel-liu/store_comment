# 使用 Eclipse Temurin JDK 17，与本地构建环境一致
FROM eclipse-temurin:17-jdk-alpine

# 安装 curl 供健康检查使用
RUN apk add --no-cache curl

# 设置工作目录
WORKDIR /app

# 复制打包后的 JAR（通配符避免版本号硬编码）
COPY target/*.jar app.jar

# 暴露端口
EXPOSE 8081

# JVM 参数（可通过环境变量覆盖）
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 健康检查（利用 Spring Boot Actuator）
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8081/actuator/health || exit 1

# 启动命令
ENTRYPOINT exec java ${JAVA_OPTS} -jar app.jar

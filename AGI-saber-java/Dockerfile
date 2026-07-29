# ===== 多阶段构建：Maven 编译 + JRE 运行 =====
# FROM maven:3.9-eclipse-temurin-17 AS builder
# WORKDIR /build
# COPY pom.xml .
# RUN mvn -B -q dependency:go-offline
# COPY src ./src
# RUN mvn -B -q -DskipTests package

# 运行时镜像（轻量 JRE 17）
# 用编译好的jar包
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates tzdata curl \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# COPY --from=builder /build/target/agi-assistant-*.jar /app/app.jar
COPY target/agi-assistant-*.jar /app/app.jar



EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

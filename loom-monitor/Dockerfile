FROM docker.io/library/eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version
COPY loom-common/build.gradle loom-common/build.gradle
COPY loom-api/build.gradle loom-api/build.gradle
COPY loom-scheduler/build.gradle loom-scheduler/build.gradle
COPY loom-worker/build.gradle loom-worker/build.gradle
COPY loom-monitor/build.gradle loom-monitor/build.gradle
COPY loom-ai/build.gradle loom-ai/build.gradle
COPY loom-common/src loom-common/src
COPY loom-monitor/src loom-monitor/src
RUN ./gradlew :loom-monitor:bootJar -x test --no-daemon

FROM docker.io/library/eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/loom-monitor/build/libs/loom-monitor-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

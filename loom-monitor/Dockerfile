FROM docker.io/library/eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true ./gradlew --version
COPY loom-common/build.gradle loom-common/build.gradle
COPY loom-api/build.gradle loom-api/build.gradle
COPY loom-scheduler/build.gradle loom-scheduler/build.gradle
COPY loom-worker/build.gradle loom-worker/build.gradle
COPY loom-monitor/build.gradle loom-monitor/build.gradle
COPY loom-ai/build.gradle loom-ai/build.gradle
COPY loom-observability/build.gradle loom-observability/build.gradle
COPY loom-task-capabilities/build.gradle loom-task-capabilities/build.gradle
COPY loom-task-capabilities/src loom-task-capabilities/src
COPY loom-common/src loom-common/src
COPY loom-monitor/src loom-monitor/src
RUN JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true ./gradlew :loom-monitor:bootJar -x test --no-daemon

FROM docker.io/library/eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/loom-monitor/build/libs/loom-monitor-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

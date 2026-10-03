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
COPY loom-common/src loom-common/src
COPY loom-worker/src loom-worker/src
RUN ./gradlew :loom-worker:bootJar -x test --no-daemon

FROM docker.io/library/eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/loom-worker/build/libs/loom-worker-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

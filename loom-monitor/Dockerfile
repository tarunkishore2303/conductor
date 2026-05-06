FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY loom-common/build.gradle loom-common/build.gradle
COPY loom-monitor/build.gradle loom-monitor/build.gradle
COPY loom-common/src loom-common/src
COPY loom-monitor/src loom-monitor/src
RUN ./gradlew :loom-monitor:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/loom-monitor/build/libs/loom-monitor-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

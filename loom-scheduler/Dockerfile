FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY loom-common/build.gradle loom-common/build.gradle
COPY loom-scheduler/build.gradle loom-scheduler/build.gradle
COPY loom-common/src loom-common/src
COPY loom-scheduler/src loom-scheduler/src
RUN ./gradlew :loom-scheduler:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/loom-scheduler/build/libs/loom-scheduler-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY loom-common/build.gradle loom-common/build.gradle
COPY loom-worker/build.gradle loom-worker/build.gradle
COPY loom-common/src loom-common/src
COPY loom-worker/src loom-worker/src
RUN ./gradlew :loom-worker:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/loom-worker/build/libs/loom-worker-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

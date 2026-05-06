FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY loom-common/build.gradle loom-common/build.gradle
COPY loom-api/build.gradle loom-api/build.gradle
COPY loom-common/src loom-common/src
COPY loom-api/src loom-api/src
RUN ./gradlew :loom-api:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/loom-api/build/libs/loom-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

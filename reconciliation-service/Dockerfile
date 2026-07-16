# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Warm the wrapper/deps cache first for faster rebuilds.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root user.
RUN groupadd --system app && useradd --system --gid app --home /app app

COPY --from=build /workspace/build/libs/*.jar /app/app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8131
ENV SERVER_PORT=8131
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

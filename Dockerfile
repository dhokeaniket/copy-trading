FROM gradle:8.14-jdk21-alpine AS build
WORKDIR /app

# Cache dependencies — only re-download when build.gradle or settings.gradle change
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon || true

# Now copy source and build
COPY src ./src
RUN gradle bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-8081} -jar app.jar"]

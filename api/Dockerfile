# syntax=docker/dockerfile:1

# Build stage: full JDK and Maven, thrown away after the build.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
# Copy only the build files first, so the dependency layer is cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Tests run in CI with Testcontainers; a Docker build cannot start containers itself.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/splitledger-api-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Runtime stage: JRE only, no build tools, no shell access as root.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
# One layer per change frequency: libraries rarely change, application classes often.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# Size the heap from the container memory limit (0.5 GB on the smallest Fargate task).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "application.jar"]

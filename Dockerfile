# syntax=docker/dockerfile:1

# ---- Build stage -----------------------------------------------------------
# Uses the project's Maven wrapper so the build is reproducible and the Maven
# version is pinned in .mvn/wrapper/maven-wrapper.properties (no host Maven needed).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Resolve dependencies first, in their own layer, so source-only changes
# don't trigger a full re-download on every build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# Now build the application.
COPY src/ src/
RUN ./mvnw -B -ntp clean package -DskipTests

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN useradd --system --uid 1001 --no-create-home appuser
USER 1001

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

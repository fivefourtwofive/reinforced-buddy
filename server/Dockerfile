# Build stage
FROM maven:3.9.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY ../pom.xml /build/parent-pom.xml
COPY . /build/server
# Copy modules needed for dependency resolution
# Expect repository root mounted; for standalone builds, run from repo root using docker buildx bake
RUN --mount=type=cache,target=/root/.m2 mvn -q -f /build/server/pom.xml -DskipTests package

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/server/target/server-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

# Build stage
FROM maven:3.9.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY ../pom.xml /build/parent-pom.xml
COPY . /build/tcp-proxy
RUN --mount=type=cache,target=/root/.m2 mvn -q -f /build/tcp-proxy/pom.xml -DskipTests package

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/tcp-proxy/target/tcp-proxy-*.jar /app/app.jar
EXPOSE 7070
ENTRYPOINT ["java","-jar","/app/app.jar"]

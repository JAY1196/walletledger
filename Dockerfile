# Build the executable Spring Boot JAR.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package

# Run the packaged application on a Java 21 JRE.
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/walletledger-0.0.1-SNAPSHOT.jar /app/walletledger.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/walletledger.jar"]

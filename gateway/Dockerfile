# Build stage: compile the reactor modules the gateway needs
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY diameter-codec/pom.xml diameter-codec/
COPY diameter-simulator/pom.xml diameter-simulator/
COPY gateway/pom.xml gateway/
COPY load-test/pom.xml load-test/
RUN mvn -q -B -pl gateway -am dependency:go-offline
COPY diameter-codec diameter-codec
COPY diameter-simulator diameter-simulator
COPY gateway gateway
RUN mvn -q -B -pl gateway -am -DskipTests package

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/gateway/target/gateway.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=20"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

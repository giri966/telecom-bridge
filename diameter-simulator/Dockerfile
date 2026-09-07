# Build stage: compile the whole reactor so the simulator picks up diameter-codec from source
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY diameter-codec/pom.xml diameter-codec/
COPY diameter-simulator/pom.xml diameter-simulator/
COPY gateway/pom.xml gateway/
COPY load-test/pom.xml load-test/
RUN mvn -q -B -pl diameter-simulator -am dependency:go-offline
COPY diameter-codec diameter-codec
COPY diameter-simulator diameter-simulator
RUN mvn -q -B -pl diameter-simulator -am -DskipTests package

# Runtime stage: JRE only
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/diameter-simulator/target/diameter-simulator.jar app.jar
EXPOSE 3868
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

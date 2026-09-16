FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY backend/pom.xml backend/pom.xml
COPY simulator/pom.xml simulator/pom.xml
COPY simulator/src simulator/src
RUN mvn -pl simulator -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --user-group metropulse
COPY --from=build /workspace/simulator/target/metropulse-simulator-*.jar app.jar
USER metropulse
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

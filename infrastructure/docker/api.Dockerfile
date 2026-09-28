FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY apps/api/pom.xml ./apps/api/pom.xml
COPY apps/api/src ./apps/api/src

RUN mvn -B -ntp -pl apps/api -am package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

COPY --from=build /workspace/apps/api/target/vcut-api-0.1.0-SNAPSHOT.jar ./app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

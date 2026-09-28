FROM maven:3.9.9-eclipse-temurin-21

WORKDIR /workspace

COPY pom.xml ./pom.xml

RUN mvn -B -ntp -q validate

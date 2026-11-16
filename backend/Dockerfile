FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system helpdesk && useradd --system --gid helpdesk helpdesk
COPY --from=build /app/target/helpdesk-api-0.1.0.jar app.jar
USER helpdesk
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]

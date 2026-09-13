FROM eclipse-temurin:21-jre

WORKDIR /app

ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

RUN mkdir -p /app/.keys

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

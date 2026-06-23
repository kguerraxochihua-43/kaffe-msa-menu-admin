FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S kaffe && adduser -S kaffe -G kaffe

WORKDIR /app
ARG JAR_FILE=target/kaffe-msa-menu-admin.jar
COPY ${JAR_FILE} app.jar

USER kaffe

EXPOSE 8086

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

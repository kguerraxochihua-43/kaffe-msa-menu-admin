FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d

WORKDIR /app
ARG JAR_FILE=target/kaffe-msa-menu-admin.jar
COPY --chown=65532:65532 ${JAR_FILE} app.jar

USER 65532:65532

EXPOSE 8086

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

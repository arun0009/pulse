# Pre-built JAR — run `make build` from examples/showcase first to populate target/.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/edge-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]

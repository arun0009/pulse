FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/downstream-*.jar /app/app.jar
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
EXPOSE 8090
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]

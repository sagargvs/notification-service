FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the agent (downloaded by your Maven plugin earlier)
COPY target/agents/opentelemetry-javaagent.jar /app/agent.jar

# Copy your actual application jar
COPY target/*.jar /app/app.jar

# Run the app with the agent active
ENTRYPOINT ["java", "-javaagent:/app/agent.jar", "-jar", "/app/app.jar"]

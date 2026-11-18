# Build stage
FROM maven:3.9.11-eclipse-temurin-21 AS builder

# Set working directory
WORKDIR /app

# Copy the project files
COPY . .

# Build and test the application
RUN mvn --batch-mode --no-transfer-progress verify

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Set working directory
WORKDIR /app

# Copy the JAR from the builder stage
COPY --from=builder /app/target/device-service.jar .

# Expose the port the app runs on
EXPOSE 8082

# Command to run the application
CMD ["java", "-jar", "device-service.jar"]

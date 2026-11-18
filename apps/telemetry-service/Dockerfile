# Runtime image
FROM python:3.12-slim

# Set working directory
WORKDIR /app

# Copy the dependency list
COPY requirements.txt ./

# Install the dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy the source code
COPY telemetry_service ./telemetry_service

# Expose the port the app runs on
EXPOSE 8083

# Command to run the application
CMD ["python", "-m", "telemetry_service"]

# Use OpenJDK as base image
FROM openjdk:17-slim

# Install required packages
RUN apt-get update && apt-get install -y \
    maven \
    git \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy the project files
COPY . .

# Build the project with Maven and install the scripts
RUN mvn clean package -Pfatjar && \
    mkdir -p bin && \
    bash install && \
    mv stitching bin/ && \
    mv resave bin/ && \
    mv detect-interestpoints bin/ && \
    mv match-interestpoints bin/ && \
    mv solver bin/ && \
    mv create-fusion-container bin/ && \
    mv affine-fusion bin/ && \
    mv nonrigid-fusion bin/ && \
    mv split-images bin/ && \
    mv downsample bin/ && \
    mv clear-interestpoints bin/ && \
    mv clear-registrations bin/ && \
    mv transform-points bin/

# Create directory for data
RUN mkdir -p /data/input

# Set environment variables
ENV JAVA_OPTS="-Xmx64g" \
    SPARK_WORKER_MEMORY=64g \
    SPARK_DRIVER_MEMORY=64g \
    SPARK_LOCAL_DIRS=/tmp \
    SPARK_WORKER_DIR=/tmp \
    SPARK_LOCAL_IP=127.0.0.1 \
    PATH="/app/bin:${PATH}"

# Default working directory
WORKDIR /data

# Default command
ENTRYPOINT ["bash"]

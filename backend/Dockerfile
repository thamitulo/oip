# --- build ---
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests && cp target/oip-backend-*.jar app.jar

# --- run ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S oip && adduser -S oip -G oip && mkdir -p /data/storage && chown -R oip:oip /data
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER oip
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

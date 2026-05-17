FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .
RUN javac -cp mysql-connector-j-9.6.0.jar TaskServer.java
CMD ["java", "-cp", ".:mysql-connector-j-9.6.0.jar", "TaskServer"]

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B package dependency:copy-dependencies \
    -DoutputDirectory=target/dependency

FROM eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install -y nginx \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/target/classes ./target/classes
COPY --from=build /app/target/dependency ./target/dependency
COPY nginx.conf /etc/nginx/templates/default.conf.template

EXPOSE 10000

CMD ["sh", "-c", "export PORT=${PORT:-10000}; envsubst '${PORT}' < /etc/nginx/templates/default.conf.template > /etc/nginx/conf.d/default.conf; java -cp 'target/classes:target/dependency/*' ChatServer & java -cp 'target/classes:target/dependency/*' WebGateway 8081 127.0.0.1 12345 & java -cp 'target/classes:target/dependency/*' WebFiles 8080 & nginx -g 'daemon off;'"]

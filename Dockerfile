# Servicio de Reservas — contenedor Docker para el clúster Kubernetes de Plataforma Northwind (ADR-005).
# Construir antes el jar: ./gradlew bootJar
# TODO(architecture): T3-E "Escalada desde un contenedor comprometido del Servicio de Reservas" — imagen
# firmada, ejecución sin root y NetworkPolicy no documentadas (docs/07 § 9.3; docs/09 § 11.1). La imagen
# corre sin root; la firma y la NetworkPolicy son del despliegue.
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S reservas && adduser -S -G reservas reservas
WORKDIR /app
COPY build/libs/servicio-de-reservas-1.1.1.jar servicio-de-reservas.jar
USER reservas

# 8080: API (I-02), detrás del ingress con TLS 1.2+; 8081: sondas de vida y disponibilidad.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/servicio-de-reservas.jar"]

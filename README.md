# Servicio de Reservas

API REST de disponibilidad, reserva y cancelación de salas de **Reservas de Salas Northwind**, construida
desde el build pack de la arquitectura **1.9.0** (binding en `.architecture.json`).

- **Contrato**: `architecture/openapi.yaml`, copia byte a byte del build pack. Es de solo lectura: las
  interfaces y los modelos de `northwind.reservas.api` se generan desde ella (`./gradlew openApiGenerate`).
- **Stack**: Java 21, Spring Boot 3.3, PostgreSQL 16 con Flyway, OpenTelemetry. Gradle Wrapper (Kotlin DSL).

## Configuración

Lo que la arquitectura no documenta no tiene valor por defecto: si falta, el servicio no arranca.

| Variable | Qué es |
|---|---|
| `RESERVAS_DB_URL`, `RESERVAS_DB_USUARIO`, `RESERVAS_DB_CLAVE` | Base de Datos de Reservas (I-04), desde el gestor de secretos del clúster (ADR-005) |
| `RESERVAS_DB_SSLMODE` | TLS de JDBC; por defecto `verify-full` |
| `RESERVAS_JWKS_URI` | Endpoint JWKS del Proveedor de Identidad Northwind (I-03) |
| `RESERVAS_CLAIM_ROLES` | Ruta del claim con el rol `recepcion` (no documentada) |
| `RESERVAS_CLAIM_SEDE` | Claim del atributo `sede` (no documentado) |
| `RESERVAS_CLAIM_CLIENTE`, `RESERVAS_CLIENTES_PERMITIDOS` | Cómo se reconoce al Portal de Reservas, el único llamador (no documentado) |
| `RESERVAS_INTERVALO_PURGA_CLAVES` | Cada cuánto se purgan las claves de idempotencia vencidas, como duración (`1h`, `PT15M`); TDR-002 opción B (no documentado) |
| `RESERVAS_TAMANO_MAXIMO_CACHE_DISPONIBILIDAD` | Máximo de entradas (sede y fecha) de la caché Caffeine de disponibilidad; ADR-109 (no documentado) |
| `RESERVAS_ESPERA_RECONEXION_ESCUCHA` | Espera entre intentos de reconectar la conexión `LISTEN` del canal `disponibilidad`, como duración (`5s`); ADR-109 (no documentado) |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | Colector OpenTelemetry de Plataforma Northwind (ADR-004) |

Puertos: `8080` para la API y `8081` para las sondas (`/actuator/health/liveness` y `/actuator/health/readiness`).

## Pruebas

```bash
./gradlew test
```

Las pruebas usan Docker (Testcontainers con PostgreSQL 16) y un endpoint JWKS local con claves RSA reales.
Cubren las invariantes INV-01 a INV-10 y las pruebas de seguridad de la part `security`.

## Pendiente de la arquitectura

Cada hueco está en el código como `TODO(architecture)` citando la pregunta y la parte del pack:
`grep -rn "TODO(architecture)" src Dockerfile`.

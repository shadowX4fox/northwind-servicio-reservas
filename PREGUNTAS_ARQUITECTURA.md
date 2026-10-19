# Servicio de Reservas — preguntas para el arquitecto

Arquitectura **Reservas de Salas Northwind - Autoservicio de reservas de salas de reunión**, versión **1.0.0**,
`pack_revision` `cd8f7a693baba1db05e56ccc03cb68a97cef376f3bb7a37b90034c09cc157c85`.

La construcción del Servicio de Reservas está detenida: el build pack no permite implementar ninguna
operación de negocio sin inventar el contrato. El repositorio solo contiene el binding (`.architecture.json`)
y los contratos copiados byte a byte en `architecture/` (sha256 verificados).

## 1. Bloqueantes — sin respuesta no se escribe código

| # | Pregunta | Parte del pack | Dónde corregirlo |
|---|----------|----------------|------------------|
| B1 | Método y ruta de "Consultar disponibilidad" (CU1) | `asset:openapi.yaml` línea 19 | `docs/components/reservas-de-salas-northwind/02-servicio-de-reservas.md` § Especificación de API |
| B2 | Método y ruta de "Crear reserva" (CU2), con la cabecera `Idempotency-Key` (ADR-103) | `asset:openapi.yaml` línea 20 | ídem |
| B3 | Método y ruta de "Cancelar reserva" (CU3), incluido el motivo de hasta 120 caracteres de recepción (ADR-105) | `asset:openapi.yaml` línea 21 | ídem |
| B4 | Esquemas de solicitud y respuesta (`components.schemas` está vacío) y los valores del campo `code` de los errores RFC 9457 (ADR-001) | `asset:openapi.yaml` línea 31 | `docs/05-integration-points.md` (bloque I-02) y el archivo del componente |
| B5 | Autenticación de I-02 (Portal de Reservas → Servicio de Reservas). ADR-002 fija la validación de JWT RS256 con JWKS y la Especificación de Componente la recoge como INV-04, pero `docs/07-security-architecture.md` no la declara para I-02; mientras tanto la interfaz debe fallar cerrada | `security` línea 11 | `docs/07-security-architecture.md` |
| B6 | Autenticación de I-03 (Servicio de Reservas → Proveedor de Identidad Northwind, lectura de JWKS); además, I-03 no tiene Interface Contract | `security` línea 19; `spec` § 2 | `docs/07-security-architecture.md`, `docs/05-integration-points.md` |
| B7 | Autenticación de I-04 (Servicio de Reservas → Base de Datos de Reservas). ADR-005 dice que las credenciales salen del gestor de secretos del clúster, pero el mecanismo de I-04 no está declarado | `security` línea 20 | `docs/07-security-architecture.md` |

## 2. Necesarias para cerrar la aceptación

| # | Pregunta | Parte del pack |
|---|----------|----------------|
| A1 | Versión de la API (`info.version` es `NOT-DOCUMENTED`) y versión del contrato en la Especificación de Componente § 3 | `asset:openapi.yaml` línea 5; `spec` línea 112 |
| A2 | URL base del servidor (`servers` omitido) | `asset:openapi.yaml` línea 13 |
| A3 | Presupuesto de latencia de cada fase (2 Validación del token, 3 Consulta de disponibilidad, 4 Creación de reserva, 5 Cancelación de reserva); solo están las metas del flujo completo (≤ 1 s y ≤ 2 s p95) | `runtime-view`, BUILD.md § Flows |
| A4 | Latencia p99, error budget, throughput pico y sostenido, techo de costo | `spec` líneas 140–144 |
| A5 | Semántica de fallo de I-02: política de reintento y presupuesto ante timeout, downstream no disponible, fallo parcial, versiones incompatibles | `interface:portal-de-reservas→servicio-de-reservas` líneas 120–124 |
| A6 | Amenazas STRIDE que la construcción debe mitigar | `security` línea 53 |
| A7 | Clasificación de los datos que maneja el componente | `security` línea 57 |
| A8 | Estado de ADR-102, ADR-103 y ADR-105 (Proposed): ¿se construye sobre ellos o se espera su aceptación? | `adr:ADR-102`, `adr:ADR-103`; ADR-105 no está en el pack aunque rige la cancelación |

## 3. Menores — no bloquean

| # | Pregunta | Parte del pack |
|---|----------|----------------|
| M1 | Equipo responsable (`**Owner:**`) | `spec` línea 9; `asset:c4-descriptor.md` |
| M2 | Host, IP, SO y dominio para la CMDB | `asset:c4-descriptor.md` (`docs/09-operational-considerations.md`) |
| M3 | Referencia del contrato en `docs/05-integration-points.md`, tablas Inbound/Outbound de `01-portal-de-reservas.md` y `02-servicio-de-reservas.md`, garantías de orden, propiedad de datos del upstream | `interface:portal-de-reservas→servicio-de-reservas` líneas 33, 82, 104, 109, 136, 138 |
| M4 | ID de los diagramas en el registro | `spec` línea 82; interface línea 58 |

## Decisión del equipo para retomar

- No hay plantilla de servicio del equipo: el proyecto se crea con **Gradle Wrapper (Kotlin DSL)**, Java 21, Spring Boot 3.3.

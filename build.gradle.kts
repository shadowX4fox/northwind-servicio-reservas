// Servicio de Reservas — arquitectura Reservas de Salas Northwind 1.3.2.
// El contrato vive en architecture/openapi.yaml (copia byte a byte del build pack); las interfaces
// y los modelos de la API se generan desde esa copia y nunca se escriben a mano.

plugins {
    java
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.14.0"
}

group = "northwind.reservas"
version = "1.1.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Testcontainers 1.19 (el de Spring Boot 3.3) usa la API 1.32 de Docker, que Docker 29 ya no acepta.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // ADR-003: PostgreSQL 16, esquema versionado con Flyway.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // ADR-004: trazas y métricas con OpenTelemetry (OTLP), W3C Trace Context; logs JSON estructurados.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val contrato = layout.projectDirectory.file("architecture/openapi.yaml")
val generado = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName = "spring"
    inputSpec = contrato.asFile.path
    outputDir = generado.get().asFile.path
    apiPackage = "northwind.reservas.api"
    modelPackage = "northwind.reservas.api.modelo"
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "useTags" to "false",
        "skipDefaultInterface" to "true",
        "openApiNullable" to "false",
        "annotationLibrary" to "none",
        "documentationProvider" to "none",
        "useResponseEntity" to "true",
        "dateLibrary" to "java8",
    )
    globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "false")
}

sourceSets {
    main {
        java.srcDir(generado.map { it.dir("src/main/java") })
    }
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

tasks.test {
    useJUnitPlatform()
}

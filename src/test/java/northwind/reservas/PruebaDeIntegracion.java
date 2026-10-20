package northwind.reservas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import northwind.reservas.dominio.CacheDisponibilidad;
import northwind.reservas.dominio.Sede;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de las pruebas: el Servicio de Reservas real, con una Base de Datos de Reservas PostgreSQL 16
 * y un endpoint JWKS que hace de Proveedor de Identidad Northwind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PruebaDeIntegracion.RelojDePrueba.class)
public abstract class PruebaDeIntegracion {

    static final ZoneId ZONA = Sede.ZONA_HORARIA;
    /** Lunes 2 de noviembre de 2026, 08:00 en la zona de las sedes de prueba. */
    static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 2, 8, 0);
    static final String PORTAL = "portal-de-reservas";

    /** Un solo contenedor para todas las clases: el contexto de Spring se reutiliza entre ellas. */
    @ServiceConnection
    static final PostgreSQLContainer<?> BASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        BASE.start();
    }

    static final RSAKey CLAVE = nuevaClave("clave-1");
    static final AtomicBoolean JWKS_DISPONIBLE = new AtomicBoolean(true);
    static final HttpServer JWKS = servidorJwks();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.hikari.data-source-properties.sslmode", () -> "disable");
        registro.add("management.otlp.metrics.export.enabled", () -> "false");
        registro.add("reservas.token.jwk-set-uri", () -> "http://localhost:" + JWKS.getAddress().getPort() + "/jwks");
        registro.add("reservas.token.claim-roles", () -> "realm_access.roles");
        registro.add("reservas.token.claim-sede", () -> "sede");
        registro.add("reservas.token.claim-cliente", () -> "azp");
        registro.add("reservas.token.clientes-permitidos", () -> PORTAL);
        // Las pruebas llaman a la purga directamente; la programada no corre durante ellas.
        registro.add("reservas.intervalo-purga-claves", () -> "P1D");
        registro.add("reservas.tamano-maximo-cache-disponibilidad", () -> "100");
        registro.add("reservas.espera-reconexion-escucha", () -> "1s");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RelojMovil reloj;

    @Autowired
    CacheDisponibilidad cache;

    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void datosIniciales() {
        JWKS_DISPONIBLE.set(true);
        reloj.fijar(AHORA);
        jdbc.sql("TRUNCATE auditoria_cancelacion, clave_idempotencia, reserva, sala").update();
        cache.vaciar();
        sala("sala-c1", "centro", "Atlántico", 4, "screen");
        sala("sala-c2", "centro", "Pacífico", 8, "screen", "videoconference");
        sala("sala-n1", "norte", "Andes", 6, "whiteboard");
    }

    void sala(String id, String sede, String nombre, int capacidad, String... equipamiento) {
        jdbc.sql("INSERT INTO sala (id, sede, nombre, capacidad, equipamiento) VALUES (?, ?, ?, ?, ?::text[])")
                .param(id).param(sede).param(nombre).param(capacidad)
                .param("{" + String.join(",", equipamiento) + "}")
                .update();
    }

    // ---- tokens --------------------------------------------------------------------------------

    static String token(String sub) {
        return token(CLAVE, claims(sub).build());
    }

    static String tokenRecepcion(String sub, String sede) {
        return token(CLAVE, claims(sub).claim("realm_access", Map.of("roles", List.of("recepcion"))).claim("sede", sede).build());
    }

    static JWTClaimsSet.Builder claims(String sub) {
        Instant emitido = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(sub)
                .claim("azp", PORTAL)
                .issueTime(Date.from(emitido))
                .expirationTime(Date.from(emitido.plusSeconds(300)));
    }

    static String token(RSAKey clave, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(clave));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static RSAKey nuevaClave(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer servidorJwks() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] cuerpo = new JWKSet(CLAVE.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            servidor.createContext("/jwks", intercambio -> {
                if (!JWKS_DISPONIBLE.get()) {
                    intercambio.sendResponseHeaders(503, -1);
                    intercambio.close();
                    return;
                }
                intercambio.getResponseHeaders().add("Content-Type", "application/json");
                intercambio.sendResponseHeaders(200, cuerpo.length);
                intercambio.getResponseBody().write(cuerpo);
                intercambio.close();
            });
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- HTTP ----------------------------------------------------------------------------------

    record Respuesta(int estado, String tipo, JsonNode cuerpo) {
        String codigo() {
            return cuerpo.path("code").asText(null);
        }
    }

    Respuesta get(String ruta, String token) {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(uri(ruta)).GET();
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        return enviar(pedido.build());
    }

    Respuesta post(String ruta, String token, Object cuerpo, Map<String, String> cabeceras) {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(uri(ruta));
        if (cuerpo == null) {
            pedido.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            try {
                pedido.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        cabeceras.forEach(pedido::header);
        return enviar(pedido.build());
    }

    Respuesta reservar(String token, UUID clave, String sala, String fecha, String inicio, String fin) {
        return post("/v1/bookings", token,
                Map.of("roomId", sala, "date", fecha, "start", inicio, "end", fin, "subject", "Reunión de equipo"),
                Map.of("Idempotency-Key", clave.toString()));
    }

    Respuesta reservar(String token, String sala, String fecha, String inicio, String fin) {
        return reservar(token, UUID.randomUUID(), sala, fecha, inicio, fin);
    }

    private URI uri(String ruta) {
        return URI.create("http://localhost:" + puerto + ruta);
    }

    private Respuesta enviar(HttpRequest pedido) {
        try {
            HttpResponse<String> r = http.send(pedido, HttpResponse.BodyHandlers.ofString());
            JsonNode cuerpo = r.body().isEmpty() ? json.createObjectNode() : json.readTree(r.body());
            return new Respuesta(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), cuerpo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- reloj ---------------------------------------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojDePrueba {
        @Bean
        @Primary
        RelojMovil relojMovil() {
            return new RelojMovil();
        }
    }

    static final class RelojMovil extends Clock {
        private volatile Instant instante = Instant.now();

        void fijar(LocalDateTime hora) {
            instante = hora.atZone(ZONA).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZONA;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instante;
        }
    }
}

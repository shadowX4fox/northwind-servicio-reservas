package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * ADR-109: caché Caffeine de disponibilidad por sede y fecha, 5 minutos, invalidada en todas las
 * réplicas con LISTEN/NOTIFY en el canal `disponibilidad`.
 */
class CacheDisponibilidadIT extends PruebaDeIntegracion {

    private static final String CONSULTA = "/v1/availability?site=centro&date=2026-11-03&start=10:00&end=11:00";
    private static final Duration ESPERA = Duration.ofSeconds(10);

    @Test
    void elAvisoDeOtraReplicaBorraLaEntrada() {
        assertThat(estadoDe("sala-c1")).isEqualTo("free");

        reservaDeOtraReplica("sala-c1", "2026-11-03 10:00", "2026-11-03 11:00");
        avisoDeOtraReplica("centro 2026-11-03");

        await().atMost(ESPERA).untilAsserted(() -> assertThat(estadoDe("sala-c1")).isEqualTo("occupied"));
    }

    @Test
    void sinAvisoLaEntradaViveHastaExpirar() {
        assertThat(estadoDe("sala-c1")).isEqualTo("free");

        // Una réplica cuyo aviso se perdió: la expiración de 5 minutos es la red de seguridad.
        reservaDeOtraReplica("sala-c1", "2026-11-03 10:00", "2026-11-03 11:00");

        reloj.fijar(AHORA.plusMinutes(4).plusSeconds(59));
        assertThat(estadoDe("sala-c1")).isEqualTo("free");

        reloj.fijar(AHORA.plusMinutes(5));
        assertThat(estadoDe("sala-c1")).isEqualTo("occupied");
    }

    @Test
    void unAvisoIlegibleSeIgnoraYLaEscuchaSigue() {
        assertThat(estadoDe("sala-c1")).isEqualTo("free");
        reservaDeOtraReplica("sala-c1", "2026-11-03 10:00", "2026-11-03 11:00");

        avisoDeOtraReplica("no es un aviso");
        avisoDeOtraReplica("centro 2026-11-03");

        await().atMost(ESPERA).untilAsserted(() -> assertThat(estadoDe("sala-c1")).isEqualTo("occupied"));
    }

    @Test
    void crearUnaReservaAvisaATodasLasReplicasYBorraLaEntradaLocal() throws SQLException {
        try (Connection escucha = escuchar()) {
            assertThat(estadoDe("sala-c1")).isEqualTo("free");

            assertThat(reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00").estado()).isEqualTo(201);

            assertThat(estadoDe("sala-c1")).isEqualTo("occupied");
            assertThat(avisos(escucha)).containsExactly("centro 2026-11-03");
        }
    }

    @Test
    void cancelarUnaReservaAvisaATodasLasReplicasYBorraLaEntradaLocal() throws SQLException {
        String codigo = reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00").cuerpo().path("code").asText();
        assertThat(estadoDe("sala-c1")).isEqualTo("occupied");

        try (Connection escucha = escuchar()) {
            assertThat(post("/v1/bookings/" + codigo + "/cancel", token("miembro-1"), null, Map.of()).estado()).isEqualTo(200);

            assertThat(estadoDe("sala-c1")).isEqualTo("free");
            assertThat(avisos(escucha)).containsExactly("centro 2026-11-03");
        }
    }

    @Test
    void unaCreacionRechazadaNoAvisa() throws SQLException {
        reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00");

        try (Connection escucha = escuchar()) {
            assertThat(reservar(token("miembro-2"), "sala-c1", "2026-11-03", "10:30", "11:30").codigo()).isEqualTo("ROOM_OCCUPIED");

            assertThat(avisos(escucha)).isEmpty();
        }
    }

    @Test
    void laEntradaEsPorSedeYFecha() {
        assertThat(estadoDe("sala-c1")).isEqualTo("free");

        reservaDeOtraReplica("sala-c1", "2026-11-03 10:00", "2026-11-03 11:00");
        avisoDeOtraReplica("centro 2026-11-04");
        avisoDeOtraReplica("norte 2026-11-03");

        // Ningún aviso nombra centro el 3: la entrada sigue.
        await().during(Duration.ofSeconds(1)).atMost(ESPERA)
                .untilAsserted(() -> assertThat(estadoDe("sala-c1")).isEqualTo("free"));
    }

    private String estadoDe(String salaId) {
        Respuesta r = get(CONSULTA, token("miembro-2"));
        assertThat(r.estado()).isEqualTo(200);
        for (var sala : r.cuerpo().path("rooms")) {
            if (sala.path("roomId").asText().equals(salaId)) {
                return sala.path("status").asText();
            }
        }
        throw new AssertionError("sala ausente: " + salaId);
    }

    private void reservaDeOtraReplica(String sala, String inicio, String fin) {
        jdbc.sql("""
                        INSERT INTO reserva (codigo, sala_id, sede, inicio, fin, asunto, miembro, estado)
                        VALUES ('RS-OTRARPL1', ?, 'centro', ?::timestamp, ?::timestamp, 'Otra réplica', 'miembro-9', 'confirmed')""")
                .param(sala).param(inicio).param(fin)
                .update();
    }

    private void avisoDeOtraReplica(String aviso) {
        jdbc.sql("SELECT pg_notify('disponibilidad', ?)").param(aviso).query((rs, n) -> null).list();
    }

    private static Connection escuchar() throws SQLException {
        Connection c = DriverManager.getConnection(BASE.getJdbcUrl(), BASE.getUsername(), BASE.getPassword());
        try (Statement s = c.createStatement()) {
            s.execute("LISTEN disponibilidad");
        }
        return c;
    }

    /** Los avisos recibidos; la respuesta HTTP llega después del commit, así que ya están en camino. */
    private static List<String> avisos(Connection escucha) throws SQLException {
        List<String> recibidos = new ArrayList<>();
        PGNotification[] lote = escucha.unwrap(PGConnection.class).getNotifications(1000);
        while (lote != null && lote.length > 0) {
            for (PGNotification n : lote) {
                recibidos.add(n.getParameter());
            }
            lote = escucha.unwrap(PGConnection.class).getNotifications(200);
        }
        return recibidos;
    }
}

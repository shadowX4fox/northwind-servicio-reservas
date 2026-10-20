package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-108: caché Caffeine de disponibilidad por sede y fecha, 5 minutos, invalidada por esta réplica. */
class CacheDisponibilidadIT extends PruebaDeIntegracion {

    private static final String CONSULTA = "/v1/availability?site=centro&date=2026-11-03&start=10:00&end=11:00";

    @Test
    void unaReservaDeOtraReplicaNoSeVeHastaQueLaEntradaExpira() {
        assertThat(estadoDe("sala-c1")).isEqualTo("free");

        reservaDeOtraReplica("sala-c1", "2026-11-03 10:00", "2026-11-03 11:00");

        reloj.fijar(AHORA.plusMinutes(4).plusSeconds(59));
        assertThat(estadoDe("sala-c1")).isEqualTo("free");

        reloj.fijar(AHORA.plusMinutes(5));
        assertThat(estadoDe("sala-c1")).isEqualTo("occupied");
    }

    @Test
    void crearUnaReservaInvalidaLaEntradaDeSuSedeYFecha() {
        assertThat(estadoDe("sala-c1")).isEqualTo("free");

        assertThat(reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00").estado()).isEqualTo(201);

        assertThat(estadoDe("sala-c1")).isEqualTo("occupied");
    }

    @Test
    void cancelarUnaReservaInvalidaLaEntradaDeSuSedeYFecha() {
        String codigo = reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00").cuerpo().path("code").asText();
        assertThat(estadoDe("sala-c1")).isEqualTo("occupied");

        assertThat(post("/v1/bookings/" + codigo + "/cancel", token("miembro-1"), null, Map.of()).estado()).isEqualTo(200);

        assertThat(estadoDe("sala-c1")).isEqualTo("free");
    }

    @Test
    void laEntradaEsPorSedeYFecha() {
        assertThat(estadoDe("sala-c1")).isEqualTo("free");

        reservar(token("miembro-1"), "sala-c1", "2026-11-04", "10:00", "11:00");
        reservaDeOtraReplica("sala-c1", "2026-11-03 10:00", "2026-11-03 11:00");

        // La reserva del 4 no invalida la entrada del 3.
        assertThat(estadoDe("sala-c1")).isEqualTo("free");
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
}

package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import northwind.reservas.dominio.PurgaClavesIdempotencia;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TDR-002, opción B: las claves de idempotencia de más de 24 horas se purgan. */
class PurgaClavesIdempotenciaIT extends PruebaDeIntegracion {

    @Autowired
    PurgaClavesIdempotencia purga;

    @Test
    void purgaLasClavesVencidasYConservaLasVigentes() {
        reservar(token("miembro-1"), "sala-c1", "2026-11-04", "10:00", "11:00");
        reloj.fijar(AHORA.plusHours(12));
        UUID vigente = UUID.randomUUID();
        reservar(token("miembro-2"), vigente, "sala-c2", "2026-11-04", "10:00", "11:00");

        reloj.fijar(AHORA.plusHours(24).plusMinutes(1));
        assertThat(purga.purgar()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT clave FROM clave_idempotencia").query(UUID.class).list()).containsExactly(vigente);
    }

    @Test
    void unaClaveDeExactamente24HorasYaEstaVencida() {
        reservar(token("miembro-1"), "sala-c1", "2026-11-04", "10:00", "11:00");

        reloj.fijar(AHORA.plusHours(24));

        assertThat(purga.purgar()).isEqualTo(1);
    }

    @Test
    void laClaveVigenteSigueDevolviendoLaRespuestaGuardadaTrasLaPurga() {
        UUID clave = UUID.randomUUID();
        Respuesta primera = reservar(token("miembro-1"), clave, "sala-c1", "2026-11-04", "10:00", "11:00");

        reloj.fijar(AHORA.plusHours(23));
        purga.purgar();
        Respuesta repetida = reservar(token("miembro-1"), clave, "sala-c1", "2026-11-04", "10:00", "11:00");

        assertThat(repetida.estado()).isEqualTo(201);
        assertThat(repetida.cuerpo()).isEqualTo(primera.cuerpo());
        assertThat(jdbc.sql("SELECT count(*) FROM reserva").query(Integer.class).single()).isEqualTo(1);
    }
}

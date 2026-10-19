package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CU3: "Mis reservas" y "Reservas de la sede" (ADR-105). */
class ListadoIT extends PruebaDeIntegracion {

    @BeforeEach
    void reservas() {
        reservar(token("miembro-1"), "sala-c1", "2026-11-02", "09:00", "10:00");
        reservar(token("miembro-1"), "sala-n1", "2026-11-03", "10:00", "11:00");
        reservar(token("miembro-2"), "sala-c2", "2026-11-03", "12:00", "13:00");
        reloj.fijar(AHORA.plusHours(1).plusMinutes(30));
    }

    @Test
    void misReservasListaSoloLasFuturasPropias() {
        Respuesta r = get("/v1/bookings", token("miembro-1"));

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo()).hasSize(1);
        assertThat(r.cuerpo().get(0).path("roomId").asText()).isEqualTo("sala-n1");
    }

    @Test
    void recepcionListaLasFuturasDeSuSede() {
        Respuesta r = get("/v1/bookings?scope=site", tokenRecepcion("recepcionista-centro", "centro"));

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo()).extracting((JsonNode b) -> b.path("roomId").asText()).containsExactly("sala-c2");
    }

    @Test
    void scopeSiteSinRolRecepcionSeNiega() {
        Respuesta r = get("/v1/bookings?scope=site", token("miembro-1"));

        assertThat(r.estado()).isEqualTo(403);
        assertThat(r.codigo()).isEqualTo("FORBIDDEN");
    }

    @Test
    void unScopeDesconocidoRespondeValidationFailed() {
        assertThat(get("/v1/bookings?scope=todas", token("miembro-1")).codigo()).isEqualTo("VALIDATION_FAILED");
    }
}

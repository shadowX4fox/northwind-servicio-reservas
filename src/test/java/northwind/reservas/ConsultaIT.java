package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CU3, paso 1 — `getBooking`: la propia, o una de la `sede` del token con rol `recepcion` (ADR-105). */
class ConsultaIT extends PruebaDeIntegracion {

    private String codigo;

    @BeforeEach
    void reservaDelMiembro() {
        codigo = reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00").cuerpo().path("code").asText();
    }

    @Test
    void elDuenoConsultaSuReserva() {
        Respuesta r = consultar(token("miembro-1"));

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo().path("code").asText()).isEqualTo(codigo);
        assertThat(r.cuerpo().path("roomId").asText()).isEqualTo("sala-c1");
        assertThat(r.cuerpo().path("site").asText()).isEqualTo("centro");
        assertThat(r.cuerpo().path("start").asText()).isEqualTo("10:00");
        assertThat(r.cuerpo().path("end").asText()).isEqualTo("11:00");
        assertThat(r.cuerpo().path("status").asText()).isEqualTo("confirmed");
    }

    @Test
    void recepcionDeLaMismaSedeConsulta() {
        assertThat(consultar(tokenRecepcion("recepcionista-centro", "centro")).estado()).isEqualTo(200);
    }

    @Test
    void otroMiembroRecibeForbidden() {
        Respuesta r = consultar(token("miembro-2"));

        assertThat(r.estado()).isEqualTo(403);
        assertThat(r.tipo()).startsWith("application/problem+json");
        assertThat(r.codigo()).isEqualTo("FORBIDDEN");
    }

    @Test
    void recepcionDeOtraSedeRecibeForbidden() {
        assertThat(consultar(tokenRecepcion("recepcionista-norte", "norte")).codigo()).isEqualTo("FORBIDDEN");
    }

    @Test
    void unCodigoInexistenteRespondeBookingNotFound() {
        Respuesta r = get("/v1/bookings/RS-NOEXISTE", token("miembro-1"));

        assertThat(r.estado()).isEqualTo(404);
        assertThat(r.codigo()).isEqualTo("BOOKING_NOT_FOUND");
    }

    @Test
    void sinCredencialesRespondeUnauthenticated() {
        Respuesta r = consultar(null);

        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.codigo()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void unaReservaCanceladaSeConsultaConSuEstado() {
        post("/v1/bookings/" + codigo + "/cancel", token("miembro-1"), null, Map.of());

        Respuesta r = consultar(token("miembro-1"));

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo().path("status").asText()).isEqualTo("cancelled");
        assertThat(r.cuerpo().path("cancelledBy").asText()).isEqualTo("miembro-1");
    }

    private Respuesta consultar(String token) {
        return get("/v1/bookings/" + codigo, token);
    }
}

package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CU3, fase 5 — INV-06, INV-07 e INV-08; ADR-105 y ADR-106. */
class CancelacionIT extends PruebaDeIntegracion {

    private String codigo;

    @BeforeEach
    void reservaDelMiembro() {
        codigo = reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00").cuerpo().path("code").asText();
    }

    @Test
    void elDuenoCancelaSinCuerpo() {
        Respuesta r = cancelar(token("miembro-1"), null);

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo().path("status").asText()).isEqualTo("cancelled");
        assertThat(r.cuerpo().path("cancelledBy").asText()).isEqualTo("miembro-1");
        assertThat(OffsetDateTime.parse(r.cuerpo().path("cancelledAt").asText()).toInstant())
                .isEqualTo(AHORA.atZone(ZONA).toInstant());
        assertThat(r.cuerpo().has("cancellationReason")).isFalse();
        assertThat(estado()).isEqualTo("cancelled");
        assertThat(auditoria()).isEqualTo(1);
    }

    @Test
    void otroMiembroRecibeForbiddenYLaReservaSigueConfirmada() {
        Respuesta r = cancelar(token("miembro-2"), Map.of("reason", "no es mía, pero la cancelo"));

        assertThat(r.estado()).isEqualTo(403);
        assertThat(r.tipo()).startsWith("application/problem+json");
        assertThat(r.codigo()).isEqualTo("FORBIDDEN");
        assertThat(estado()).isEqualTo("confirmed");
        assertThat(auditoria()).isZero();
    }

    @Test
    void recepcionDeLaMismaSedeCancelaConMotivo() {
        Respuesta r = cancelar(tokenRecepcion("recepcionista-centro", "centro"), Map.of("reason", "Mantenimiento de la sala"));

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo().path("cancelledBy").asText()).isEqualTo("recepcionista-centro");
        assertThat(r.cuerpo().path("cancellationReason").asText()).isEqualTo("Mantenimiento de la sala");
        String motivoAuditado = jdbc.sql("SELECT motivo FROM auditoria_cancelacion WHERE codigo_reserva = ?")
                .param(codigo).query(String.class).single();
        assertThat(motivoAuditado).isEqualTo("Mantenimiento de la sala");
    }

    @Test
    void recepcionSinMotivoRespondeValidationFailed() {
        assertThat(cancelar(tokenRecepcion("recepcionista-centro", "centro"), null).codigo()).isEqualTo("VALIDATION_FAILED");
        assertThat(cancelar(tokenRecepcion("recepcionista-centro", "centro"), Map.of("reason", " ")).codigo())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(estado()).isEqualTo("confirmed");
    }

    @Test
    void unMotivoDeMasDe120CaracteresRespondeValidationFailed() {
        Respuesta r = cancelar(tokenRecepcion("recepcionista-centro", "centro"), Map.of("reason", "x".repeat(121)));

        assertThat(r.codigo()).isEqualTo("VALIDATION_FAILED");
        assertThat(estado()).isEqualTo("confirmed");
    }

    @Test
    void unMotivoDeMenosDe10CaracteresRespondeValidationFailed() {
        Respuesta r = cancelar(tokenRecepcion("recepcionista-centro", "centro"), Map.of("reason", "x".repeat(9)));

        assertThat(r.codigo()).isEqualTo("VALIDATION_FAILED");
        assertThat(estado()).isEqualTo("confirmed");
    }

    @Test
    void unMotivoDe10CaracteresEsValido() {
        Respuesta r = cancelar(tokenRecepcion("recepcionista-centro", "centro"), Map.of("reason", "x".repeat(10)));

        assertThat(r.estado()).isEqualTo(200);
        assertThat(estado()).isEqualTo("cancelled");
    }

    @Test
    void recepcionDeOtraSedeRecibeForbidden() {
        assertThat(cancelar(tokenRecepcion("recepcionista-norte", "norte"), Map.of("reason", "Mantenimiento de la sala")).codigo()).isEqualTo("FORBIDDEN");
        assertThat(estado()).isEqualTo("confirmed");
    }

    @Test
    void laSedeSinElRolRecepcionNoAutoriza() {
        String sinRol = token(CLAVE, claims("miembro-2").claim("sede", "centro").build());

        assertThat(cancelar(sinRol, Map.of("reason", "Mantenimiento de la sala")).codigo()).isEqualTo("FORBIDDEN");
    }

    @Test
    void unaReservaYaIniciadaRespondeBookingInProgress() {
        reloj.fijar(AHORA.plusDays(1).plusHours(2));

        Respuesta r = cancelar(token("miembro-1"), null);

        assertThat(r.estado()).isEqualTo(409);
        assertThat(r.codigo()).isEqualTo("BOOKING_IN_PROGRESS");
        assertThat(estado()).isEqualTo("confirmed");
    }

    @Test
    void unCodigoInexistenteRespondeBookingNotFound() {
        Respuesta r = post("/v1/bookings/RS-NOEXISTE/cancel", token("miembro-1"), null, Map.of());

        assertThat(r.estado()).isEqualTo(404);
        assertThat(r.codigo()).isEqualTo("BOOKING_NOT_FOUND");
    }

    private Respuesta cancelar(String token, Object cuerpo) {
        return post("/v1/bookings/" + codigo + "/cancel", token, cuerpo, Map.of());
    }

    private String estado() {
        return jdbc.sql("SELECT estado FROM reserva WHERE codigo = ?").param(codigo).query(String.class).single();
    }

    private int auditoria() {
        return jdbc.sql("SELECT count(*) FROM auditoria_cancelacion WHERE codigo_reserva = ?")
                .param(codigo).query(Integer.class).single();
    }
}

package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** CU2, fase 4 — INV-01 e INV-03 a INV-05; ADR-102 y ADR-103. */
class ReservaIT extends PruebaDeIntegracion {

    @Test
    void creaUnaReservaConfirmada() {
        Respuesta r = reservar(token("miembro-1"), "sala-c2", "2026-11-03", "10:00", "11:30");

        assertThat(r.estado()).isEqualTo(201);
        assertThat(r.cuerpo().path("code").asText()).matches("RS-[A-Za-z0-9]{8}");
        assertThat(r.cuerpo().path("roomId").asText()).isEqualTo("sala-c2");
        assertThat(r.cuerpo().path("site").asText()).isEqualTo("centro");
        assertThat(r.cuerpo().path("date").asText()).isEqualTo("2026-11-03");
        assertThat(r.cuerpo().path("start").asText()).isEqualTo("10:00");
        assertThat(r.cuerpo().path("end").asText()).isEqualTo("11:30");
        assertThat(r.cuerpo().path("subject").asText()).isEqualTo("Reunión de equipo");
        assertThat(r.cuerpo().path("memberId").asText()).isEqualTo("miembro-1");
        assertThat(r.cuerpo().path("status").asText()).isEqualTo("confirmed");
        assertThat(r.cuerpo().has("cancelledBy")).isFalse();
        assertThat(r.cuerpo().has("cancelledAt")).isFalse();
        assertThat(r.cuerpo().has("cancellationReason")).isFalse();
    }

    @Test
    void laMismaClaveDevuelveLaRespuestaGuardadaSinCrearUnaSegundaReserva() {
        UUID clave = UUID.randomUUID();
        Respuesta primera = reservar(token("miembro-1"), clave, "sala-c1", "2026-11-03", "10:00", "11:00");
        Respuesta segunda = reservar(token("miembro-1"), clave, "sala-c1", "2026-11-03", "10:00", "11:00");

        assertThat(segunda.estado()).isEqualTo(201);
        assertThat(segunda.cuerpo()).isEqualTo(primera.cuerpo());
        assertThat(reservasEnBase()).isEqualTo(1);
    }

    @Test
    void laClaveSeConserva24Horas() {
        UUID clave = UUID.randomUUID();
        Respuesta primera = reservar(token("miembro-1"), clave, "sala-c1", "2026-11-05", "10:00", "11:00");

        reloj.fijar(AHORA.plusHours(23).plusMinutes(59));
        assertThat(reservar(token("miembro-1"), clave, "sala-c1", "2026-11-05", "10:00", "11:00").cuerpo())
                .isEqualTo(primera.cuerpo());

        reloj.fijar(AHORA.plusHours(24).plusMinutes(1));
        Respuesta vencida = reservar(token("miembro-1"), clave, "sala-c1", "2026-11-05", "10:00", "11:00");
        assertThat(vencida.codigo()).isEqualTo("ROOM_OCCUPIED");
    }

    @Test
    void laClaveEsDeCadaMiembro() {
        UUID clave = UUID.randomUUID();
        reservar(token("miembro-1"), clave, "sala-c1", "2026-11-03", "10:00", "11:00");

        Respuesta otro = reservar(token("miembro-2"), clave, "sala-c2", "2026-11-03", "10:00", "11:00");

        assertThat(otro.estado()).isEqualTo(201);
        assertThat(otro.cuerpo().path("memberId").asText()).isEqualTo("miembro-2");
    }

    @Test
    void unaReservaSuperpuestaParaLaMismaSalaRespondeRoomOccupied() {
        reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00");

        Respuesta r = reservar(token("miembro-2"), "sala-c1", "2026-11-03", "10:30", "11:30");

        assertThat(r.estado()).isEqualTo(409);
        assertThat(r.tipo()).startsWith("application/problem+json");
        assertThat(r.codigo()).isEqualTo("ROOM_OCCUPIED");
        assertThat(reservasEnBase()).isEqualTo(1);
    }

    @Test
    void unaReservaCanceladaNoOcupaLaSala() {
        String codigo = reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00").cuerpo().path("code").asText();
        post("/v1/bookings/" + codigo + "/cancel", token("miembro-1"), null, Map.of());

        assertThat(reservar(token("miembro-2"), "sala-c1", "2026-11-03", "10:00", "11:00").estado()).isEqualTo(201);
    }

    @Test
    void reservasConcurrentesDeLaMismaSalaConfirmanSoloUna() throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Respuesta>> pedidos = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String miembro = "miembro-" + i;
                pedidos.add(() -> reservar(token(miembro), "sala-c2", "2026-11-04", "14:00", "15:00"));
            }
            List<Integer> estados = new ArrayList<>();
            for (Future<Respuesta> f : hilos.invokeAll(pedidos)) {
                estados.add(f.get().estado());
            }
            assertThat(estados).containsOnly(201, 409);
            assertThat(estados).filteredOn(e -> e == 201).hasSize(1);
        } finally {
            hilos.shutdownNow();
        }
    }

    @Test
    void laCuartaReservaFuturaDelDiaRespondeDailyLimitReached() {
        String t = token("miembro-1");
        assertThat(reservar(t, "sala-c1", "2026-11-03", "08:00", "09:00").estado()).isEqualTo(201);
        assertThat(reservar(t, "sala-c1", "2026-11-03", "09:00", "10:00").estado()).isEqualTo(201);
        assertThat(reservar(t, "sala-n1", "2026-11-03", "10:00", "11:00").estado()).isEqualTo(201);

        Respuesta cuarta = reservar(t, "sala-c2", "2026-11-03", "15:00", "16:00");

        assertThat(cuarta.estado()).isEqualTo(409);
        assertThat(cuarta.codigo()).isEqualTo("DAILY_LIMIT_REACHED");
        assertThat(reservar(t, "sala-c2", "2026-11-04", "15:00", "16:00").estado()).isEqualTo(201);
        assertThat(reservar(token("miembro-2"), "sala-c2", "2026-11-03", "15:00", "16:00").estado()).isEqualTo(201);
    }

    @Test
    void unaReservaCanceladaNoCuentaParaElLimiteDiario() {
        String t = token("miembro-1");
        reservar(t, "sala-c1", "2026-11-03", "08:00", "09:00");
        reservar(t, "sala-c1", "2026-11-03", "09:00", "10:00");
        String codigo = reservar(t, "sala-n1", "2026-11-03", "10:00", "11:00").cuerpo().path("code").asText();
        post("/v1/bookings/" + codigo + "/cancel", t, null, Map.of());

        assertThat(reservar(t, "sala-c2", "2026-11-03", "15:00", "16:00").estado()).isEqualTo(201);
    }

    @Test
    void unaFranjaInvalidaRespondeValidationFailed() {
        String t = token("miembro-1");
        assertThat(reservar(t, "sala-c1", "2026-11-03", "10:10", "11:00").codigo()).isEqualTo("VALIDATION_FAILED");
        assertThat(reservar(t, "sala-c1", "2026-11-03", "08:00", "16:30").codigo()).isEqualTo("VALIDATION_FAILED");
        assertThat(reservar(t, "sala-c1", "2026-11-02", "07:00", "08:30").codigo()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void unaSalaInexistenteRespondeRoomNotFound() {
        Respuesta r = reservar(token("miembro-1"), "sala-inexistente", "2026-11-03", "10:00", "11:00");

        assertThat(r.estado()).isEqualTo(404);
        assertThat(r.codigo()).isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    void unAsuntoDeExactamente120CaracteresSeAcepta() {
        Respuesta r = post("/v1/bookings", token("miembro-1"),
                Map.of("roomId", "sala-c1", "date", "2026-11-03", "start", "10:00", "end", "11:00", "subject", "x".repeat(120)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));

        assertThat(r.estado()).isEqualTo(201);
        assertThat(r.cuerpo().path("subject").asText()).hasSize(120);
    }

    @Test
    void unAsuntoDeMasDe120CaracteresRespondeValidationFailed() {
        Respuesta r = post("/v1/bookings", token("miembro-1"),
                Map.of("roomId", "sala-c1", "date", "2026-11-03", "start", "10:00", "end", "11:00", "subject", "x".repeat(121)),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));

        assertThat(r.estado()).isEqualTo(400);
        assertThat(r.codigo()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void unPedidoIncompletoRespondeValidationFailed() {
        Respuesta r = post("/v1/bookings", token("miembro-1"),
                Map.of("roomId", "sala-c1", "date", "2026-11-03", "start", "10:00", "end", "11:00"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));

        assertThat(r.codigo()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void sinIdempotencyKeyValidaRespondeValidationFailed() {
        Map<String, String> cuerpo = Map.of("roomId", "sala-c1", "date", "2026-11-03", "start", "10:00", "end", "11:00", "subject", "s");

        assertThat(post("/v1/bookings", token("miembro-1"), cuerpo, Map.of()).codigo()).isEqualTo("VALIDATION_FAILED");
        assertThat(post("/v1/bookings", token("miembro-1"), cuerpo, Map.of("Idempotency-Key", "no-es-uuid")).codigo())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(reservasEnBase()).isZero();
    }

    private int reservasEnBase() {
        return jdbc.sql("SELECT count(*) FROM reserva").query(Integer.class).single();
    }
}

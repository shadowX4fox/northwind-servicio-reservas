package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CU1, fase 3 — INV-01 e INV-02. */
class DisponibilidadIT extends PruebaDeIntegracion {

    @Test
    void calculaLaDisponibilidadPorSedeFechaYFranja() {
        assertThat(reservar(token("miembro-1"), "sala-c1", "2026-11-03", "10:00", "11:00").estado()).isEqualTo(201);

        Respuesta r = get("/v1/availability?site=centro&date=2026-11-03&start=10:30&end=12:00", token("miembro-2"));

        assertThat(r.estado()).isEqualTo(200);
        JsonNode salas = r.cuerpo().path("rooms");
        assertThat(salas).hasSize(2);
        assertThat(sala(salas, "sala-c1").path("status").asText()).isEqualTo("occupied");
        assertThat(sala(salas, "sala-c2").path("status").asText()).isEqualTo("free");
        assertThat(sala(salas, "sala-c2").path("capacity").asInt()).isEqualTo(8);
        assertThat(sala(salas, "sala-c2").path("equipment")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("screen", "videoconference");
        assertThat(r.cuerpo().path("suggestedSlots")).isEmpty();
    }

    @Test
    void unaReservaQueTerminaAlInicioDeLaFranjaNoLaOcupa() {
        reservar(token("miembro-1"), "sala-c1", "2026-11-03", "09:00", "10:00");

        Respuesta r = get("/v1/availability?site=centro&date=2026-11-03&start=10:00&end=11:00", token("miembro-2"));

        assertThat(sala(r.cuerpo().path("rooms"), "sala-c1").path("status").asText()).isEqualTo("free");
    }

    @Test
    void sinSalasLibresSugiereLasTresFranjasDeLaMismaDuracionMasCercanas() {
        reservar(token("miembro-1"), "sala-c1", "2026-11-03", "09:00", "12:00");
        reservar(token("miembro-2"), "sala-c2", "2026-11-03", "10:00", "11:30");

        Respuesta r = get("/v1/availability?site=centro&date=2026-11-03&start=10:00&end=11:00", token("miembro-3"));

        assertThat(r.cuerpo().path("rooms")).allSatisfy(s -> assertThat(s.path("status").asText()).isEqualTo("occupied"));
        List<String> sugeridas = new ArrayList<>();
        r.cuerpo().path("suggestedSlots").forEach(f -> sugeridas.add(f.path("start").asText() + "-" + f.path("end").asText()));
        // Franjas de 1 hora, como la pedida. Distancia al inicio pedido (10:00): 09:00 → 60 min;
        // 08:30 y 11:30 → 90 min, y ante igual distancia gana la posterior. 09:30, 10:30 y 11:00 no
        // tienen sala libre la hora entera.
        assertThat(sugeridas).containsExactly("09:00-10:00", "11:30-12:30", "08:30-09:30");
    }

    @Test
    void lasFranjasSugeridasNuncaEstanEnElPasado() {
        reservar(token("miembro-1"), "sala-c1", "2026-11-02", "10:00", "11:00");
        reservar(token("miembro-2"), "sala-c2", "2026-11-02", "10:00", "11:00");
        reloj.fijar(AHORA.withHour(9).withMinute(30));

        Respuesta r = get("/v1/availability?site=centro&date=2026-11-02&start=10:00&end=11:00", token("miembro-3"));

        // 09:00 estaría a 60 minutos, como 11:00, pero ya pasó.
        assertThat(r.cuerpo().path("suggestedSlots")).extracting(f -> f.path("start").asText())
                .containsExactly("11:00", "11:30", "12:00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "site=centro&date=2026-11-03&start=10:15&end=11:00",   // no es múltiplo de 30 minutos
        "site=centro&date=2026-11-03&start=08:00&end=16:30",   // más de 8 horas
        "site=centro&date=2026-11-02&start=07:30&end=09:00",   // en el pasado
        "site=centro&date=2026-11-03&start=11:00&end=11:00",   // sin duración
        "site=centro&date=2026-11-03&start=10:00",             // falta el fin
        "date=2026-11-03&start=10:00&end=11:00",               // falta la sede
        "site=sur&date=2026-11-03&start=10:00&end=11:00",      // sede desconocida
        "site=centro&date=03-11-2026&start=10:00&end=11:00",   // fecha fuera de ISO 8601
        "site=centro&date=2026-11-03&start=10h00&end=11:00"    // hora fuera de HH:mm
    })
    void unaFranjaInvalidaRespondeValidationFailed(String consulta) {
        Respuesta r = get("/v1/availability?" + consulta, token("miembro-1"));

        assertThat(r.estado()).isEqualTo(400);
        assertThat(r.tipo()).startsWith("application/problem+json");
        assertThat(r.codigo()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void unaFranjaDeOchoHorasExactasEsValida() {
        assertThat(get("/v1/availability?site=norte&date=2026-11-03&start=08:00&end=16:00", token("miembro-1")).estado())
                .isEqualTo(200);
    }

    private static JsonNode sala(JsonNode salas, String id) {
        for (JsonNode s : salas) {
            if (s.path("roomId").asText().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("sin sala " + id);
    }
}

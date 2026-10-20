package northwind.reservas.configuracion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Solución temporal de la zona horaria de las sedes, hasta la respuesta a
 * https://github.com/shadowX4fox/northwind-reservas/issues/62 (Tier 0:
 * https://github.com/shadowX4fox/northwind-reservas/issues/65): la da el despliegue en
 * {@code RESERVAS_ZONA_HORARIA}, sin valor por defecto.
 */
class ZonaHorariaSedesTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(Habilitar.class, ConfiguracionReloj.class)
            .withPropertyValues(
                    "reservas.token.jwk-set-uri=http://localhost/jwks",
                    "reservas.token.claim-roles=realm_access.roles",
                    "reservas.token.claim-sede=sede",
                    "reservas.token.claim-cliente=azp",
                    "reservas.token.clientes-permitidos=portal-de-reservas",
                    "reservas.intervalo-purga-claves=P1D",
                    "reservas.tamano-maximo-cache-disponibilidad=100",
                    "reservas.espera-reconexion-escucha=1s");

    @Test
    void elRelojDeNegocioUsaLaZonaQueDaElDespliegue() {
        contexto.withPropertyValues("reservas.zona-horaria=America/Bogota").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(PropiedadesReservas.class).zonaHoraria()).isEqualTo(ZoneId.of("America/Bogota"));
            assertThat(ctx.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("America/Bogota"));
        });
    }

    @Test
    void sinZonaElServicioNoArranca() {
        contexto.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void conUnaZonaInvalidaElServicioNoArranca() {
        contexto.withPropertyValues("reservas.zona-horaria=Sede/Centro").run(ctx -> assertThat(ctx).hasFailed());
    }

    @EnableConfigurationProperties(PropiedadesReservas.class)
    static class Habilitar {}
}

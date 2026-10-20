package northwind.reservas.configuracion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** ADR-110: la hora de negocio de las 3 sedes es la de {@code America/Bogota}; el despliegue no la elige. */
class ZonaHorariaSedesTest {

    private final ApplicationContextRunner contexto =
            new ApplicationContextRunner().withUserConfiguration(ConfiguracionReloj.class);

    @Test
    void elRelojDeNegocioUsaAmericaBogota() {
        contexto.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("America/Bogota"));
        });
    }

    @Test
    void laZonaYaNoSeLeeDelDespliegue() {
        contexto.withPropertyValues("reservas.zona-horaria=Europe/Madrid", "RESERVAS_ZONA_HORARIA=Europe/Madrid")
                .run(ctx -> assertThat(ctx.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("America/Bogota")));
    }
}

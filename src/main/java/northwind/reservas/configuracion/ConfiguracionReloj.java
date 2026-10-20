package northwind.reservas.configuracion;

import java.time.Clock;
import northwind.reservas.dominio.Sede;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * La hora de negocio es la hora local de las sedes (ADR-110), nunca la del servidor ni la de la base.
 * La usan INV-01, INV-04 y ADR-105.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionReloj {

    @Bean
    Clock reloj() {
        return Clock.system(Sede.ZONA_HORARIA);
    }
}

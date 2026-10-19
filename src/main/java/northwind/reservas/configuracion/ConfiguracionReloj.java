package northwind.reservas.configuracion;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** La hora de negocio es la hora local de las sedes, nunca la del servidor ni la de la base. */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionReloj {

    @Bean
    Clock reloj(PropiedadesReservas propiedades) {
        return Clock.system(propiedades.zonaHoraria());
    }
}

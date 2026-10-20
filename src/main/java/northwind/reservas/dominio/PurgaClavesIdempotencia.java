package northwind.reservas.dominio;

import java.time.Clock;
import java.time.OffsetDateTime;
import northwind.reservas.configuracion.PropiedadesReservas;
import northwind.reservas.persistencia.RepositorioReservas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * TDR-002, opción B: el Servicio de Reservas borra periódicamente las claves de idempotencia vencidas
 * (más de 24 horas, ADR-103). Varias réplicas pueden purgar a la vez: el borrado es idempotente.
 */
@Component
@EnableScheduling
public class PurgaClavesIdempotencia implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PurgaClavesIdempotencia.class);

    private final RepositorioReservas repositorio;
    private final PropiedadesReservas propiedades;
    private final Clock reloj;

    public PurgaClavesIdempotencia(RepositorioReservas repositorio, PropiedadesReservas propiedades, Clock reloj) {
        this.repositorio = repositorio;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registro) {
        registro.addFixedDelayTask(new FixedDelayTask(
                this::purgar, propiedades.intervaloPurgaClaves(), propiedades.intervaloPurgaClaves()));
    }

    /** Borra las claves vencidas y devuelve cuántas. */
    public int purgar() {
        OffsetDateTime vigenteDesde = OffsetDateTime.now(reloj).minus(ServicioReservas.VIGENCIA_CLAVE);
        int purgadas = repositorio.purgarClavesVencidas(vigenteDesde);
        if (purgadas > 0) {
            log.info("claves de idempotencia vencidas purgadas: {}", purgadas);
        }
        return purgadas;
    }
}

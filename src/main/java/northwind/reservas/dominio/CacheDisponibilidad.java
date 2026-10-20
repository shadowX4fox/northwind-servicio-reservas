package northwind.reservas.dominio;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import northwind.reservas.configuracion.PropiedadesReservas;
import northwind.reservas.persistencia.RepositorioReservas;
import northwind.reservas.persistencia.RepositorioReservas.Ocupacion;
import northwind.reservas.persistencia.RepositorioReservas.SalaFila;
import org.springframework.stereotype.Component;

/**
 * ADR-108: la disponibilidad por sede y fecha en una caché Caffeine dentro de cada réplica del
 * Servicio de Reservas. La entrada expira 5 minutos después de escribirse; la réplica que crea o
 * cancela una reserva invalida la suya, las otras réplicas no se enteran (TDR-001).
 */
@Component
public class CacheDisponibilidad {

    /** ADR-108: cada entrada expira 5 minutos después de escribirse. */
    static final Duration VIGENCIA_ENTRADA = Duration.ofMinutes(5);

    /** Las salas de la sede y sus reservas confirmadas de ese día. */
    record Entrada(List<SalaFila> salas, List<Ocupacion> ocupacion) {}

    private record Clave(Sede sede, LocalDate fecha) {}

    private final RepositorioReservas repositorio;
    private final Cache<Clave, Entrada> entradas;

    public CacheDisponibilidad(RepositorioReservas repositorio, PropiedadesReservas propiedades, Clock reloj) {
        this.repositorio = repositorio;
        this.entradas = Caffeine.newBuilder()
                .expireAfterWrite(VIGENCIA_ENTRADA)
                .maximumSize(propiedades.tamanoMaximoCacheDisponibilidad())
                // La expiración sigue el reloj de negocio, no el del sistema.
                .ticker(() -> {
                    Instant ahora = reloj.instant();
                    return ahora.getEpochSecond() * 1_000_000_000L + ahora.getNano();
                })
                .build();
    }

    /** La entrada vigente; sin ella, lee la Base de Datos de Reservas y la guarda. */
    Entrada entrada(Sede sede, LocalDate fecha) {
        return entradas.get(new Clave(sede, fecha), c -> new Entrada(
                List.copyOf(repositorio.salasDeSede(c.sede())),
                List.copyOf(repositorio.ocupacionDeSede(c.sede(), c.fecha()))));
    }

    void invalidar(Sede sede, LocalDate fecha) {
        entradas.invalidate(new Clave(sede, fecha));
    }

    /** Vacía la caché de esta réplica. */
    public void vaciar() {
        entradas.invalidateAll();
    }
}

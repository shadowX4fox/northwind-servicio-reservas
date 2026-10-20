package northwind.reservas.dominio;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import northwind.reservas.configuracion.PropiedadesReservas;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * ADR-109: cada réplica del Servicio de Reservas mantiene una conexión {@code LISTEN} al canal
 * {@code disponibilidad} de la Base de Datos de Reservas, fuera del pool, y borra su entrada de la
 * caché al recibir un aviso. Si la conexión se pierde, los avisos de ese intervalo se pierden; al
 * reconectar, la réplica vacía su caché (opción A de la deuda registrada en
 * https://github.com/shadowX4fox/northwind-reservas/issues/52), y la expiración de 5 minutos sigue
 * como red de seguridad (ADR-109).
 */
@Component
public class EscuchaDisponibilidad implements SmartLifecycle {

    static final String CANAL = "disponibilidad";

    private static final Logger log = LoggerFactory.getLogger(EscuchaDisponibilidad.class);
    /** Cada cuánto el hilo deja de esperar avisos para comprobar si debe parar. */
    private static final int ESPERA_MS = 500;

    private final HikariDataSource pool;
    private final CacheDisponibilidad cache;
    private final PropiedadesReservas propiedades;
    private volatile boolean activa;
    private volatile Connection conexion;
    private Thread hilo;

    public EscuchaDisponibilidad(HikariDataSource pool, CacheDisponibilidad cache, PropiedadesReservas propiedades) {
        this.pool = pool;
        this.cache = cache;
        this.propiedades = propiedades;
    }

    @Override
    public void start() {
        activa = true;
        hilo = Thread.ofPlatform().name("escucha-disponibilidad").daemon().start(this::escuchar);
    }

    @Override
    public void stop() {
        activa = false;
        cerrar();
        if (hilo != null) {
            hilo.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return activa;
    }

    private void escuchar() {
        boolean reconexion = false;
        while (activa) {
            try {
                conexion = conectar();
                try (Statement s = conexion.createStatement()) {
                    s.execute("LISTEN " + CANAL);
                }
                if (reconexion) {
                    // Los avisos del intervalo sin conexión se perdieron. Se vacía después del LISTEN,
                    // para que ningún aviso caiga entre el vaciado y la escucha (issue #52, opción A).
                    cache.vaciar();
                    log.info("escucha de disponibilidad reconectada; caché vaciada");
                } else {
                    log.info("escucha de disponibilidad conectada");
                }
                PGConnection pg = conexion.unwrap(PGConnection.class);
                while (activa) {
                    PGNotification[] avisos = pg.getNotifications(ESPERA_MS);
                    if (avisos != null) {
                        for (PGNotification aviso : avisos) {
                            recibir(aviso.getParameter());
                        }
                    }
                }
            } catch (SQLException e) {
                if (!activa) {
                    return;
                }
                log.warn("escucha de disponibilidad perdida; se reconecta: {}", e.getMessage());
                reconexion = true;
                cerrar();
                esperarReconexion();
            }
        }
    }

    private void recibir(String aviso) {
        CacheDisponibilidad.deAviso(aviso).ifPresentOrElse(
                clave -> cache.invalidar(clave.sede(), clave.fecha()),
                () -> log.warn("aviso de disponibilidad ilegible; se ignora"));
    }

    /** Una conexión propia, fuera del pool, con la URL, las credenciales y las propiedades del pool. */
    private Connection conectar() throws SQLException {
        Properties p = new Properties();
        p.putAll(pool.getDataSourceProperties());
        if (pool.getUsername() != null) {
            p.setProperty("user", pool.getUsername());
        }
        if (pool.getPassword() != null) {
            p.setProperty("password", pool.getPassword());
        }
        return DriverManager.getConnection(pool.getJdbcUrl(), p);
    }

    private void esperarReconexion() {
        try {
            Thread.sleep(propiedades.esperaReconexionEscucha());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cerrar() {
        Connection c = conexion;
        conexion = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException e) {
                log.debug("cierre de la escucha de disponibilidad", e);
            }
        }
    }
}

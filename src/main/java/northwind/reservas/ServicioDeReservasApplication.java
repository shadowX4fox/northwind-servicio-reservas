package northwind.reservas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servicio de Reservas — API REST de disponibilidad, reserva y cancelación de las salas de
 * Reservas de Salas Northwind (arquitectura 1.5.1).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ServicioDeReservasApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServicioDeReservasApplication.class, args);
    }
}

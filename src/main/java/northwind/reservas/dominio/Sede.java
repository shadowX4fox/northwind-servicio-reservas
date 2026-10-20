package northwind.reservas.dominio;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

/** Sedes de Northwind Cowork; los valores son los del parámetro `site` del contrato. */
public enum Sede {
    CENTRO("centro"),
    NORTE("norte"),
    PUERTO("puerto");

    /**
     * INV-14, ADR-110: las 3 sedes comparten la zona {@code America/Bogota}; las franjas del contrato y de la
     * Base de Datos de Reservas son hora local de la sede, sin desfase.
     */
    public static final ZoneId ZONA_HORARIA = ZoneId.of("America/Bogota");

    private final String valor;

    Sede(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    public static Optional<Sede> de(String valor) {
        return Arrays.stream(values()).filter(s -> s.valor.equals(valor)).findFirst();
    }
}

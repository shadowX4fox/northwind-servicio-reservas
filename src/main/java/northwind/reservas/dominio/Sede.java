package northwind.reservas.dominio;

import java.util.Arrays;
import java.util.Optional;

/** Sedes de Northwind Cowork; los valores son los del parámetro `site` del contrato. */
public enum Sede {
    CENTRO("centro"),
    NORTE("norte"),
    PUERTO("puerto");

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

package northwind.reservas.dominio;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Franja de una fecha en hora local de la sede. INV-01: múltiplo de 30 minutos, 8 horas o menos y
 * no en el pasado; en otro caso `VALIDATION_FAILED`.
 */
public record Franja(LocalDate fecha, LocalTime inicio, LocalTime fin) {

    public static final Duration PASO = Duration.ofMinutes(30);
    private static final Duration DURACION_MAXIMA = Duration.ofHours(8);
    private static final DateTimeFormatter HH_MM =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    /** Valida y construye la franja; `ahora` es la hora local de las sedes. */
    public static Franja de(LocalDate fecha, String inicio, String fin, LocalDateTime ahora) {
        if (fecha == null || inicio == null || fin == null) {
            throw ErrorDeNegocio.validacion("fecha, inicio y fin son obligatorios");
        }
        Franja franja = new Franja(fecha, hora(inicio), hora(fin));
        if (franja.inicio.getMinute() % 30 != 0 || franja.fin.getMinute() % 30 != 0) {
            throw ErrorDeNegocio.validacion("la franja no es múltiplo de 30 minutos");
        }
        if (!franja.fin.isAfter(franja.inicio)) {
            throw ErrorDeNegocio.validacion("el fin de la franja no es posterior al inicio");
        }
        if (Duration.between(franja.inicio, franja.fin).compareTo(DURACION_MAXIMA) > 0) {
            throw ErrorDeNegocio.validacion("la franja dura más de 8 horas");
        }
        if (franja.comienzo().isBefore(ahora)) {
            throw ErrorDeNegocio.validacion("la franja está en el pasado");
        }
        return franja;
    }

    private static LocalTime hora(String valor) {
        try {
            return LocalTime.parse(valor, HH_MM);
        } catch (DateTimeParseException e) {
            throw ErrorDeNegocio.validacion("hora fuera del formato HH:mm");
        }
    }

    public static String formato(LocalTime hora) {
        return hora.format(HH_MM);
    }

    public LocalDateTime comienzo() {
        return fecha.atTime(inicio);
    }

    public LocalDateTime termino() {
        return fecha.atTime(fin);
    }
}

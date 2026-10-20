package northwind.reservas.configuracion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Valores que la arquitectura 1.6.0 no documenta y que el despliegue debe dar. Ninguno tiene
 * valor por defecto: sin ellos el Servicio de Reservas no arranca, en lugar de suponerlos.
 *
 * @param zonaHoraria TODO(architecture): la arquitectura no documenta la zona horaria de las sedes;
 *     sin ella no se decide si una franja "está en el pasado" (INV-01), si una reserva es "futura"
 *     (INV-04) ni si "ya inició" (ADR-105). Parte: spec § 4. Escalado al arquitecto:
 *     https://github.com/shadowX4fox/northwind-reservas/issues/62 — hasta su respuesta el despliegue
 *     la da en {@code RESERVAS_ZONA_HORARIA}, sin valor por defecto.
 * @param token claims del JWT del Proveedor de Identidad Northwind
 * @param intervaloPurgaClaves TODO(architecture): TDR-002 (opción B) pide que el Servicio de Reservas
 *     borre las claves de idempotencia vencidas "de forma periódica", pero no documenta cada cuánto.
 * @param tamanoMaximoCacheDisponibilidad TODO(architecture): ADR-109 dice "Tamaño máximo de la caché:
 *     Not documented"; número máximo de entradas (sede y fecha) de la caché de disponibilidad.
 * @param esperaReconexionEscucha TODO(architecture): ADR-109 no documenta cómo se reconecta la conexión
 *     {@code LISTEN} perdida; espera entre intentos. Mientras tanto, la expiración de 5 minutos cubre
 *     los avisos perdidos.
 */
@Validated
@ConfigurationProperties("reservas")
public record PropiedadesReservas(
        @NotNull ZoneId zonaHoraria,
        @NotNull @Valid Token token,
        @NotNull @DurationMin(seconds = 1) Duration intervaloPurgaClaves,
        @NotNull @Positive Long tamanoMaximoCacheDisponibilidad,
        @NotNull @DurationMin(millis = 1) Duration esperaReconexionEscucha) {

    /**
     * @param jwkSetUri endpoint JWKS del Proveedor de Identidad Northwind (I-03, ADR-002)
     * @param claimRoles TODO(architecture): ruta del claim que lleva el rol `recepcion` (ADR-105
     *     dice que "el rol `recepcion` y el atributo `sede` vienen en el token" pero no en qué
     *     claim). Ruta con puntos, por ejemplo una ruta anidada a una lista de roles.
     * @param claimSede TODO(architecture): nombre del claim del atributo `sede` (ADR-105).
     * @param claimCliente TODO(architecture): claim que identifica al llamador. `security` línea 13
     *     dice "Cualquier otro llamador se rechaza" pero no cómo se reconoce al Portal de Reservas.
     * @param clientesPermitidos valores de {@code claimCliente} aceptados: solo el Portal de Reservas
     */
    public record Token(
            @NotBlank String jwkSetUri,
            @NotBlank String claimRoles,
            @NotBlank String claimSede,
            @NotBlank String claimCliente,
            @NotEmpty List<@NotBlank String> clientesPermitidos) {}
}

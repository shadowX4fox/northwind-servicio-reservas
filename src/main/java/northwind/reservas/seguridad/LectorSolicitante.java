package northwind.reservas.seguridad;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import northwind.reservas.configuracion.PropiedadesReservas;
import northwind.reservas.dominio.Sede;
import northwind.reservas.dominio.Solicitante;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Lee `sub`, el rol `recepcion` y el atributo `sede` del JWT validado (ADR-002, ADR-105). */
@Component
public class LectorSolicitante {

    static final String ROL_RECEPCION = "recepcion";

    private final PropiedadesReservas.Token token;

    public LectorSolicitante(PropiedadesReservas propiedades) {
        this.token = propiedades.token();
    }

    public Solicitante actual() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken autenticacion)) {
            throw new IllegalStateException("solicitud sin JWT validado");
        }
        Map<String, Object> claims = autenticacion.getToken().getClaims();
        boolean recepcion = claim(claims, token.claimRoles())
                .map(roles -> roles instanceof Collection<?> lista ? lista.contains(ROL_RECEPCION) : ROL_RECEPCION.equals(roles))
                .orElse(false);
        // TODO(architecture): formato del atributo `sede` no documentado; se acepta solo el valor
        // exacto del parámetro `site` (`centro`, `norte`, `puerto`).
        Optional<Sede> sede = claim(claims, token.claimSede())
                .filter(String.class::isInstance)
                .flatMap(valor -> Sede.de((String) valor));
        return new Solicitante(autenticacion.getToken().getSubject(), recepcion, sede);
    }

    /** Ruta con puntos dentro de los claims, por ejemplo una lista de roles anidada. */
    private static Optional<Object> claim(Map<String, Object> claims, String ruta) {
        Object actual = claims;
        for (String parte : ruta.split("\\.")) {
            if (!(actual instanceof Map<?, ?> mapa)) {
                return Optional.empty();
            }
            actual = mapa.get(parte);
        }
        return Optional.ofNullable(actual);
    }
}

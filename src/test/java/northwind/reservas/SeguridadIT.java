package northwind.reservas;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** security § Pruebas que la construcción debe pasar, e INV-10: sin token válido, `UNAUTHENTICATED` (401). */
class SeguridadIT extends PruebaDeIntegracion {

    private static final String RUTA = "/v1/bookings";

    @Test
    void unaLlamadaAI02SinCredencialesSeRechaza() {
        Respuesta r = get(RUTA, null);

        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.tipo()).startsWith("application/problem+json");
        assertThat(r.codigo()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void unaLlamadaDesdeUnLlamadorQueNoFiguraEnEntradaSeRechaza() {
        String otroCliente = token(CLAVE, claims("miembro-1").claim("azp", "otro-cliente").build());
        String sinCliente = token(CLAVE, claims("miembro-1").claim("azp", null).build());

        assertThat(get(RUTA, otroCliente).codigo()).isEqualTo("UNAUTHENTICATED");
        assertThat(get(RUTA, sinCliente).codigo()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void unTokenFirmadoConOtraClaveSeRechaza() {
        RSAKey ajena = nuevaClave(CLAVE.getKeyID());

        assertThat(get(RUTA, token(ajena, claims("miembro-1").build())).estado()).isEqualTo(401);
    }

    @Test
    void unTokenVencidoSeRechaza() {
        Instant hace = Instant.now().minusSeconds(3600);
        String vencido = token(CLAVE, claims("miembro-1")
                .issueTime(Date.from(hace))
                .expirationTime(Date.from(hace.plusSeconds(300)))
                .build());

        assertThat(get(RUTA, vencido).codigo()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void unTokenSinSubSeRechaza() {
        assertThat(get(RUTA, token(CLAVE, claims(null).build())).estado()).isEqualTo(401);
    }

    @Test
    void sinClavesJwksDisponiblesLaSolicitudSeRechaza() {
        // T7-D, I-03: una clave que la caché no tiene obliga a pedir el JWKS; el proveedor no responde.
        JWKS_DISPONIBLE.set(false);
        RSAKey nueva = nuevaClave("clave-desconocida");

        assertThat(get(RUTA, token(nueva, claims("miembro-1").build())).codigo()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void unTokenValidoDelPortalDeReservasPasa() {
        assertThat(get(RUTA, token("miembro-1")).estado()).isEqualTo(200);
    }

    @Test
    void cualquierRutaFueraDeLaApiSeNiega() {
        assertThat(get("/actuator/env", token("miembro-1")).estado()).isIn(401, 403);
        assertThat(get("/otra", null).estado()).isEqualTo(401);
    }
}

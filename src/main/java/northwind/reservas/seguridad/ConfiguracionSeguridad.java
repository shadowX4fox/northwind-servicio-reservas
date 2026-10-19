package northwind.reservas.seguridad;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import northwind.reservas.api.modelo.Problem.CodeEnum;
import northwind.reservas.configuracion.PropiedadesReservas;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Obligaciones de seguridad del Servicio de Reservas (part `security`): denegar por defecto; solo el
 * Portal de Reservas llama, por I-02, con un JWT RS256 validado con JWKS en cada solicitud (ADR-002).
 *
 * <p>TODO(architecture): T2-D "Inundación de solicitudes sin autenticar contra la API pública" — el
 * límite de tasa por IP y cliente va en el ingress; el "límite por usuario en el servicio" y el máximo
 * de réplicas del HPA no tienen valor documentado, así que el servicio no aplica ninguno.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionSeguridad {

    /** Sondas de vida y disponibilidad del clúster (ADR-005), en el puerto de gestión. */
    @Bean
    @Order(1)
    SecurityFilterChain sondas(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.to(HealthEndpoint.class))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        // El despacho de error solo llega tras una solicitud ya autorizada y conserva su código HTTP real.
        return http.authorizeHttpRequests(a -> a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .oauth2ResourceServer(o -> o.jwt(j -> {})
                        .authenticationEntryPoint((req, res, e) -> problema(res, HttpStatus.UNAUTHORIZED, CodeEnum.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, e) -> problema(res, HttpStatus.FORBIDDEN, CodeEnum.FORBIDDEN))
                        // I-03: sin claves JWKS disponibles la solicitud se rechaza (401), no termina en 500.
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <F extends BearerTokenAuthenticationFilter> F postProcess(F filtro) {
                                filtro.setAuthenticationFailureHandler((req, res, e) ->
                                        problema(res, HttpStatus.UNAUTHORIZED, CodeEnum.UNAUTHENTICATED));
                                return filtro;
                            }
                        }))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problema(res, HttpStatus.UNAUTHORIZED, CodeEnum.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, ex) -> problema(res, HttpStatus.FORBIDDEN, CodeEnum.FORBIDDEN)))
                .build();
    }

    /**
     * I-03: claves JWKS del Proveedor de Identidad Northwind, con la caché local del decodificador;
     * sin claves, la validación falla y la solicitud se rechaza (deny by default).
     * TODO(architecture): TTL y refresco de la caché de claves no documentados — security T7-D.
     */
    @Bean
    JwtDecoder decodificadorJwt(PropiedadesReservas propiedades) {
        PropiedadesReservas.Token token = propiedades.token();
        NimbusJwtDecoder decodificador = NimbusJwtDecoder.withJwkSetUri(token.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                // `memberId` y `cancelledBy` son el `sub` del token: sin `sub` no hay a quién atribuir nada.
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()),
                soloLlamadoresPermitidos(token.claimCliente(), token.clientesPermitidos())));
        return decodificador;
    }

    /** security § Entrada: "Cualquier otro llamador se rechaza". */
    static OAuth2TokenValidator<Jwt> soloLlamadoresPermitidos(String claim, List<String> permitidos) {
        OAuth2Error rechazo = new OAuth2Error("invalid_token", "llamador no permitido", null);
        return jwt -> {
            Object valor = jwt.getClaims().get(claim);
            return valor instanceof String cliente && permitidos.contains(cliente)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(rechazo);
        };
    }

    private static void problema(HttpServletResponse respuesta, HttpStatus estado, CodeEnum codigo) throws IOException {
        respuesta.setStatus(estado.value());
        if (estado == HttpStatus.UNAUTHORIZED) {
            respuesta.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.getWriter().write("{\"code\":\"" + codigo.getValue() + "\"}");
    }
}

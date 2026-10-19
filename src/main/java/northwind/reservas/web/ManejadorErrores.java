package northwind.reservas.web;

import northwind.reservas.api.modelo.Problem;
import northwind.reservas.api.modelo.Problem.CodeEnum;
import northwind.reservas.dominio.ErrorDeNegocio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errores RFC 9457 (`application/problem+json`) con el campo `code` estable (INV-11, ADR-001).
 * El esquema `Problem` declara solo `code`, obligatorio; el cuerpo lleva solo `code`.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(ErrorDeNegocio.class)
    ResponseEntity<Problem> negocio(ErrorDeNegocio e) {
        return problema(e.estado(), e.codigo());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<Problem> validacion(Exception e) {
        return problema(HttpStatus.BAD_REQUEST, CodeEnum.VALIDATION_FAILED);
    }

    /**
     * Fase 3: "Base de Datos de Reservas no disponible — Error RFC 9457 visible" (docs/04).
     * TODO(architecture): ningún `code` del contrato nombra este caso; se responde 503 sin `code`.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Problem> baseNoDisponible(DataAccessException e) {
        log.error("Base de Datos de Reservas no disponible", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem());
    }

    private static ResponseEntity<Problem> problema(HttpStatus estado, CodeEnum codigo) {
        return ResponseEntity.status(estado)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem().code(codigo));
    }
}

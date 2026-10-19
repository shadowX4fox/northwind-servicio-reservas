package northwind.reservas.dominio;

import northwind.reservas.api.modelo.Problem.CodeEnum;
import org.springframework.http.HttpStatus;

/** Rechazo con un `code` estable del contrato (INV-11, ADR-001). */
public class ErrorDeNegocio extends RuntimeException {

    private final CodeEnum codigo;
    private final HttpStatus estado;

    private ErrorDeNegocio(CodeEnum codigo, HttpStatus estado, String detalle) {
        super(detalle);
        this.codigo = codigo;
        this.estado = estado;
    }

    public static ErrorDeNegocio validacion(String detalle) {
        return new ErrorDeNegocio(CodeEnum.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, detalle);
    }

    public static ErrorDeNegocio noAutorizado() {
        return new ErrorDeNegocio(CodeEnum.FORBIDDEN, HttpStatus.FORBIDDEN, "no autorizado");
    }

    public static ErrorDeNegocio reservaNoEncontrada() {
        return new ErrorDeNegocio(CodeEnum.BOOKING_NOT_FOUND, HttpStatus.NOT_FOUND, "reserva inexistente");
    }

    public static ErrorDeNegocio salaOcupada() {
        return new ErrorDeNegocio(CodeEnum.ROOM_OCCUPIED, HttpStatus.CONFLICT, "sala ocupada");
    }

    public static ErrorDeNegocio limiteDiario() {
        return new ErrorDeNegocio(CodeEnum.DAILY_LIMIT_REACHED, HttpStatus.CONFLICT, "límite diario");
    }

    public static ErrorDeNegocio reservaEnCurso() {
        return new ErrorDeNegocio(CodeEnum.BOOKING_IN_PROGRESS, HttpStatus.CONFLICT, "reserva en curso");
    }

    public CodeEnum codigo() {
        return codigo;
    }

    public HttpStatus estado() {
        return estado;
    }
}

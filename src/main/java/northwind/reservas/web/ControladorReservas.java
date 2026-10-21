package northwind.reservas.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import northwind.reservas.api.V1Api;
import northwind.reservas.api.modelo.AvailabilityResponse;
import northwind.reservas.api.modelo.Booking;
import northwind.reservas.api.modelo.BookingRequest;
import northwind.reservas.api.modelo.CancellationRequest;
import northwind.reservas.dominio.ServicioReservas;
import northwind.reservas.seguridad.LectorSolicitante;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** I-02: la API REST que el Portal de Reservas llama, tal como la declara architecture/openapi.yaml. */
@RestController
public class ControladorReservas implements V1Api {

    private final ServicioReservas servicio;
    private final LectorSolicitante solicitante;

    public ControladorReservas(ServicioReservas servicio, LectorSolicitante solicitante) {
        this.servicio = servicio;
        this.solicitante = solicitante;
    }

    @Override
    public ResponseEntity<AvailabilityResponse> getAvailability(String site, LocalDate date, String start, String end) {
        return ResponseEntity.ok(servicio.disponibilidad(site, date, start, end));
    }

    @Override
    public ResponseEntity<Booking> createBooking(UUID idempotencyKey, BookingRequest bookingRequest) {
        Booking creada = servicio.crear(solicitante.actual(), idempotencyKey, bookingRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(creada);
    }

    @Override
    public ResponseEntity<List<Booking>> listBookings(String scope) {
        return ResponseEntity.ok(servicio.listar(solicitante.actual(), scope));
    }

    @Override
    public ResponseEntity<Booking> getBooking(String code) {
        return ResponseEntity.ok(servicio.consultar(solicitante.actual(), code));
    }

    /** El cuerpo es opcional para el dueño: Spring no hereda `required = false` de la interfaz generada. */
    @Override
    public ResponseEntity<Booking> cancelBooking(
            String code, @Valid @RequestBody(required = false) CancellationRequest cancellationRequest) {
        String motivo = cancellationRequest == null ? null : cancellationRequest.getReason();
        return ResponseEntity.ok(servicio.cancelar(solicitante.actual(), code, motivo));
    }
}

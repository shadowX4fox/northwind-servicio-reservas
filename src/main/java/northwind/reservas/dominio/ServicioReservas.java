package northwind.reservas.dominio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import northwind.reservas.api.modelo.AvailabilityResponse;
import northwind.reservas.api.modelo.AvailabilityResponseSuggestedSlotsInner;
import northwind.reservas.api.modelo.Booking;
import northwind.reservas.api.modelo.BookingRequest;
import northwind.reservas.api.modelo.RoomAvailability;
import northwind.reservas.persistencia.RepositorioReservas;
import northwind.reservas.persistencia.RepositorioReservas.Ocupacion;
import northwind.reservas.persistencia.RepositorioReservas.ReservaFila;
import northwind.reservas.persistencia.RepositorioReservas.SalaFila;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reglas de disponibilidad, reserva y cancelación del Servicio de Reservas (spec § 4). */
@Service
public class ServicioReservas {

    private static final Logger log = LoggerFactory.getLogger(ServicioReservas.class);

    private static final int LIMITE_DIARIO = 3;
    private static final int FRANJAS_SUGERIDAS = 3;
    /** ADR-103: una clave de idempotencia y su respuesta se conservan 24 horas. */
    static final Duration VIGENCIA_CLAVE = Duration.ofHours(24);
    private static final String VIOLACION_DE_EXCLUSION = "23P01";
    private static final String ALFANUMERICOS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final RepositorioReservas repositorio;
    private final CacheDisponibilidad cache;
    private final TransactionTemplate transaccion;
    private final ObjectMapper json;
    private final Clock reloj;
    private final SecureRandom aleatorio = new SecureRandom();

    public ServicioReservas(
            RepositorioReservas repositorio,
            CacheDisponibilidad cache,
            TransactionTemplate transaccion,
            ObjectMapper json,
            Clock reloj) {
        this.repositorio = repositorio;
        this.cache = cache;
        this.transaccion = transaccion;
        this.json = json;
        this.reloj = reloj;
    }

    // ---- CU1: consultar disponibilidad (fase 3) ------------------------------------------------

    public AvailabilityResponse disponibilidad(String site, LocalDate fecha, String inicio, String fin) {
        // TODO(architecture): "obligatoriedad de site, date, start y end" no está documentada
        // (asset:openapi.yaml línea 31). Sin los cuatro no hay disponibilidad que calcular, así que
        // la ausencia de uno responde VALIDATION_FAILED.
        if (site == null) {
            throw ErrorDeNegocio.validacion("site es obligatorio");
        }
        Sede sede = Sede.de(site).orElseThrow(() -> ErrorDeNegocio.validacion("sede desconocida"));
        LocalDateTime ahora = LocalDateTime.now(reloj);
        Franja franja = Franja.de(fecha, inicio, fin, ahora);

        // Fase 3 — ADR-109: la caché Caffeine; sin entrada vigente, la Base de Datos de Reservas.
        CacheDisponibilidad.Entrada entrada = cache.entrada(sede, fecha);
        List<SalaFila> salas = entrada.salas();
        List<Ocupacion> ocupacion = entrada.ocupacion();
        Set<String> ocupadas = salasOcupadas(ocupacion, franja.comienzo(), franja.termino());

        AvailabilityResponse respuesta = new AvailabilityResponse();
        for (SalaFila sala : salas) {
            respuesta.addRoomsItem(new RoomAvailability()
                    .roomId(sala.id())
                    .name(sala.nombre())
                    .capacity(sala.capacidad())
                    .equipment(sala.equipamiento().stream()
                            .map(RoomAvailability.EquipmentEnum::fromValue)
                            .toList())
                    .status(ocupadas.contains(sala.id())
                            ? RoomAvailability.StatusEnum.OCCUPIED
                            : RoomAvailability.StatusEnum.FREE));
        }
        if (!salas.isEmpty() && ocupadas.size() == salas.size()) {
            respuesta.suggestedSlots(franjasSugeridas(salas, ocupacion, franja, ahora));
        }
        return respuesta;
    }

    /**
     * CU1, alternativa 1 — INV-02 y `AvailabilityResponse.suggestedSlots`: hasta 3 franjas de la misma
     * duración, día y sede, las más próximas en el tiempo a la pedida, antes o después y nunca en el
     * pasado; ante igual distancia, la posterior. Las franjas empiezan en múltiplos de 30 minutos (INV-01).
     *
     * TODO(architecture): runtime-view (Flujo 1, Delta) dice "las 3 franjas de 30 minutos más
     * cercanas", y el contrato y la spec (INV-02) dicen "misma duración"; se implementa el contrato.
     */
    private List<AvailabilityResponseSuggestedSlotsInner> franjasSugeridas(
            List<SalaFila> salas, List<Ocupacion> ocupacion, Franja pedida, LocalDateTime ahora) {
        Duration duracion = Duration.between(pedida.inicio(), pedida.fin());
        List<LocalTime> candidatas = new ArrayList<>();
        // Una franja que terminaría a medianoche o después se omite: su fin no sería posterior a su inicio (Franja).
        for (LocalTime t = LocalTime.MIDNIGHT;
                Duration.ofSeconds(t.toSecondOfDay()).plus(duracion).compareTo(Duration.ofDays(1)) < 0;
                t = t.plus(Franja.PASO)) {
            LocalDateTime comienzo = pedida.fecha().atTime(t);
            LocalDateTime termino = comienzo.plus(duracion);
            if (!comienzo.isBefore(ahora)
                    && salasOcupadas(ocupacion, comienzo, termino).size() < salas.size()) {
                candidatas.add(t);
            }
        }
        long pedidaEnMinutos = pedida.inicio().toSecondOfDay() / 60;
        return candidatas.stream()
                .sorted(Comparator.comparingLong((LocalTime t) -> Math.abs(t.toSecondOfDay() / 60 - pedidaEnMinutos))
                        .thenComparing(Comparator.reverseOrder()))
                .limit(FRANJAS_SUGERIDAS)
                .map(t -> new AvailabilityResponseSuggestedSlotsInner()
                        .start(Franja.formato(t))
                        .end(Franja.formato(t.plus(duracion))))
                .toList();
    }

    private static Set<String> salasOcupadas(List<Ocupacion> ocupacion, LocalDateTime inicio, LocalDateTime fin) {
        Set<String> ocupadas = new HashSet<>();
        for (Ocupacion o : ocupacion) {
            if (o.inicio().isBefore(fin) && o.fin().isAfter(inicio)) {
                ocupadas.add(o.salaId());
            }
        }
        return ocupadas;
    }

    // ---- CU2: crear reserva (fase 4) -----------------------------------------------------------

    public Booking crear(Solicitante solicitante, UUID clave, BookingRequest pedido) {
        if (pedido.getDate() == null) {
            throw ErrorDeNegocio.validacion("date es obligatorio");
        }
        try {
            return invalidarCache(transaccion.execute(estado -> crearEnTransaccion(solicitante, clave, pedido)));
        } catch (ClaveVigente e) {
            // Otra solicitud con la misma clave confirmó primero: se devuelve su respuesta (ADR-103).
            return respuestaGuardada(solicitante.miembro(), clave)
                    .orElseThrow(() -> new IllegalStateException("clave de idempotencia sin respuesta", e));
        }
    }

    private Booking crearEnTransaccion(Solicitante solicitante, UUID clave, BookingRequest pedido) {
        String miembro = solicitante.miembro();
        repositorio.bloquearMiembroEnFecha(miembro, pedido.getDate());

        // Fase 4, paso 1: la clave se busca antes de insertar (ADR-103, INV-05).
        Optional<Booking> guardada = respuestaGuardada(miembro, clave);
        if (guardada.isPresent()) {
            return guardada.get();
        }

        LocalDateTime ahora = LocalDateTime.now(reloj);
        Franja franja = Franja.de(pedido.getDate(), pedido.getStart(), pedido.getEnd(), ahora);
        // TODO(architecture): la respuesta a un `roomId` inexistente no está documentada; ningún `code`
        // del contrato la nombra. Se responde VALIDATION_FAILED.
        SalaFila sala = repositorio.sala(pedido.getRoomId())
                .orElseThrow(() -> ErrorDeNegocio.validacion("sala desconocida"));

        // Fase 4, paso 3 — INV-04: un miembro no supera 3 reservas futuras por día.
        if (repositorio.reservasFuturasDelDia(miembro, franja.fecha(), ahora) >= LIMITE_DIARIO) {
            throw ErrorDeNegocio.limiteDiario();
        }

        ReservaFila reserva = new ReservaFila(
                codigoNuevo(), sala.id(), sala.sede(), franja.comienzo(), franja.termino(),
                pedido.getSubject(), miembro, "confirmed", null, null, null);
        try {
            repositorio.insertarReserva(reserva);
        } catch (DataIntegrityViolationException e) {
            // Fase 4, paso 5 — ADR-102, INV-03: la base rechaza la superposición; el código no la coordina.
            if (VIOLACION_DE_EXCLUSION.equals(estadoSql(e))) {
                throw ErrorDeNegocio.salaOcupada();
            }
            throw e;
        }
        avisarDisponibilidad(Sede.de(reserva.sede()).orElseThrow(), franja.fecha());

        Booking creada = aBooking(reserva);
        OffsetDateTime instante = OffsetDateTime.now(reloj);
        if (!repositorio.guardarRespuesta(miembro, clave, aJson(creada), instante, instante.minus(VIGENCIA_CLAVE))) {
            throw new ClaveVigente();
        }
        // TODO(architecture): T1-R "Un miembro niega haber creado una reserva" — la creación por I-02 no
        // tiene registro de auditoría documentado (docs/07 § 9.4); queda solo la fila con `miembro` y
        // `creada_en`, y este log sin datos personales.
        log.info("reserva creada: codigo={} sede={}", reserva.codigo(), reserva.sede());
        return creada;
    }

    private Optional<Booking> respuestaGuardada(String miembro, UUID clave) {
        OffsetDateTime vigenteDesde = OffsetDateTime.now(reloj).minus(VIGENCIA_CLAVE);
        return repositorio.respuestaGuardada(miembro, clave, vigenteDesde).map(this::deJson);
    }

    private String codigoNuevo() {
        String codigo;
        do {
            StringBuilder sb = new StringBuilder("RS-");
            for (int i = 0; i < 8; i++) {
                sb.append(ALFANUMERICOS.charAt(aleatorio.nextInt(ALFANUMERICOS.length())));
            }
            codigo = sb.toString();
        } while (repositorio.existeCodigo(codigo));
        return codigo;
    }

    // ---- CU3: listar reservas ------------------------------------------------------------------

    public List<Booking> listar(Solicitante solicitante, String alcance) {
        LocalDateTime ahora = LocalDateTime.now(reloj);
        // TODO(architecture): "Reservas futuras" no dice si incluye las canceladas; se listan todas
        // las que empiezan después de ahora, con su `status`.
        if (alcance == null) {
            return repositorio.futurasDelMiembro(solicitante.miembro(), ahora).stream()
                    .map(ServicioReservas::aBooking)
                    .toList();
        }
        if (!"site".equals(alcance)) {
            throw ErrorDeNegocio.validacion("scope desconocido");
        }
        // `scope=site` sin rol `recepcion` de una sede: 403 FORBIDDEN (listBookings, ADR-105).
        if (!solicitante.recepcion() || solicitante.sede().isEmpty()) {
            throw ErrorDeNegocio.noAutorizado();
        }
        return repositorio.futurasDeLaSede(solicitante.sede().get(), ahora).stream()
                .map(ServicioReservas::aBooking)
                .toList();
    }

    // ---- CU3: cancelar reserva (fase 5) --------------------------------------------------------

    public Booking cancelar(Solicitante solicitante, String codigo, String motivo) {
        return invalidarCache(transaccion.execute(estado -> {
            ReservaFila reserva = repositorio.reservaParaActualizar(codigo)
                    .orElseThrow(ErrorDeNegocio::reservaNoEncontrada);

            // Fase 5, paso 3 — ADR-105, INV-06: el dueño, o recepción de la sede de la sala.
            boolean esDueno = reserva.miembro().equals(solicitante.miembro());
            Sede sedeDeLaSala = Sede.de(reserva.sede()).orElseThrow();
            if (!esDueno && !solicitante.esRecepcionDe(sedeDeLaSala)) {
                throw ErrorDeNegocio.noAutorizado();
            }
            // TODO(architecture): cancelar una reserva ya cancelada no está documentado; se devuelve
            // tal como está, sin una segunda auditoría.
            if ("cancelled".equals(reserva.estado())) {
                return aBooking(reserva);
            }
            LocalDateTime ahora = LocalDateTime.now(reloj);
            if (!reserva.inicio().isAfter(ahora)) {
                throw ErrorDeNegocio.reservaEnCurso();
            }
            // INV-07: la cancelación por el rol `recepcion` exige `reason` de 10 a 120 caracteres; la
            // longitud la valida `CancellationRequest` (minLength 10, maxLength 120) antes de llegar aquí.
            // TODO(architecture): no se documenta si un recepcionista que cancela su propia reserva
            // cancela "por el rol recepcion"; aquí el motivo se exige cuando no es el dueño.
            if (!esDueno && (motivo == null || motivo.isBlank())) {
                throw ErrorDeNegocio.validacion("el motivo es obligatorio para recepción");
            }

            // Fase 5, paso 5 — INV-08: cancelación y auditoría en la misma transacción (ADR-106).
            OffsetDateTime instante = OffsetDateTime.now(reloj);
            repositorio.cancelar(codigo, solicitante.miembro(), instante, motivo);
            avisarDisponibilidad(sedeDeLaSala, reserva.inicio().toLocalDate());
            log.info("reserva cancelada: codigo={} sede={} recepcion={}", codigo, reserva.sede(), !esDueno);
            return aBooking(new ReservaFila(
                    reserva.codigo(), reserva.salaId(), reserva.sede(), reserva.inicio(), reserva.fin(),
                    reserva.asunto(), reserva.miembro(), "cancelled", solicitante.miembro(), instante, motivo));
        }));
    }

    /** ADR-109: en la transacción, el aviso a todas las réplicas para que borren su entrada. */
    private void avisarDisponibilidad(Sede sede, LocalDate fecha) {
        repositorio.avisarDisponibilidad(EscuchaDisponibilidad.CANAL, CacheDisponibilidad.aviso(sede, fecha));
    }

    /**
     * Tras confirmar la transacción, esta réplica borra su entrada sin esperar a su propio aviso
     * (ADR-109); las demás la borran al recibirlo.
     */
    private Booking invalidarCache(Booking reserva) {
        // `Booking.site` no tiene tipo en el contrato (Object); lleva el valor de la sede.
        cache.invalidar(Sede.de(String.valueOf(reserva.getSite())).orElseThrow(), reserva.getDate());
        return reserva;
    }

    // ---- conversión ----------------------------------------------------------------------------

    private static Booking aBooking(ReservaFila r) {
        // TODO(architecture): `Booking.site` no tiene tipo en el contrato (asset:openapi.yaml línea 314);
        // se emite el valor de la sede tal como lo nombra el parámetro `site` de CU1.
        Booking booking = new Booking()
                .code(r.codigo())
                .roomId(r.salaId())
                .site(r.sede())
                .date(r.inicio().toLocalDate())
                .start(Franja.formato(r.inicio().toLocalTime()))
                .end(Franja.formato(r.fin().toLocalTime()))
                .subject(r.asunto())
                .memberId(r.miembro())
                .status(Booking.StatusEnum.fromValue(r.estado()));
        if ("cancelled".equals(r.estado())) {
            booking.cancelledBy(r.canceladaPor())
                    .cancelledAt(r.canceladaEn())
                    .cancellationReason(r.motivoCancelacion());
        }
        return booking;
    }

    private String aJson(Booking booking) {
        try {
            return json.writeValueAsString(booking);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Booking deJson(String valor) {
        try {
            return json.readValue(valor, Booking.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String estadoSql(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** La clave sigue vigente con otra respuesta: se deshace la transacción y se devuelve esa. */
    private static final class ClaveVigente extends RuntimeException {
        ClaveVigente() {
            super(null, null, false, false);
        }
    }
}

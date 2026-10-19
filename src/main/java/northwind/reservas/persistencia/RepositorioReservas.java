package northwind.reservas.persistencia;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import northwind.reservas.dominio.Sede;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso del Servicio de Reservas a la Base de Datos de Reservas (I-04). */
@Repository
public class RepositorioReservas {

    private static final String COLUMNAS_RESERVA = """
            codigo, sala_id, sede, inicio, fin, asunto, miembro, estado,
            cancelada_por, cancelada_en, motivo_cancelacion""";

    private final JdbcClient jdbc;

    public RepositorioReservas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SalaFila(String id, String sede, String nombre, int capacidad, List<String> equipamiento) {}

    public record ReservaFila(
            String codigo,
            String salaId,
            String sede,
            LocalDateTime inicio,
            LocalDateTime fin,
            String asunto,
            String miembro,
            String estado,
            String canceladaPor,
            OffsetDateTime canceladaEn,
            String motivoCancelacion) {}

    public record Ocupacion(String salaId, LocalDateTime inicio, LocalDateTime fin) {}

    public List<SalaFila> salasDeSede(Sede sede) {
        return jdbc.sql("""
                        SELECT id, sede, nombre, capacidad, equipamiento FROM sala
                        WHERE sede = ? ORDER BY capacidad, nombre""")
                .param(sede.valor())
                .query(RepositorioReservas::sala)
                .list();
    }

    public Optional<SalaFila> sala(String id) {
        return jdbc.sql("SELECT id, sede, nombre, capacidad, equipamiento FROM sala WHERE id = ?")
                .param(id)
                .query(RepositorioReservas::sala)
                .optional();
    }

    /** Reservas confirmadas de la sede que tocan la fecha, para calcular ocupación y franjas. */
    public List<Ocupacion> ocupacionDeSede(Sede sede, LocalDate fecha) {
        return jdbc.sql("""
                        SELECT sala_id, inicio, fin FROM reserva
                        WHERE sede = ? AND estado = 'confirmed' AND inicio < ? AND fin > ?""")
                .param(sede.valor())
                .param(fecha.plusDays(1).atStartOfDay())
                .param(fecha.atStartOfDay())
                .query((rs, n) -> new Ocupacion(
                        rs.getString("sala_id"),
                        rs.getObject("inicio", LocalDateTime.class),
                        rs.getObject("fin", LocalDateTime.class)))
                .list();
    }

    /** Serializa las creaciones de un miembro en una fecha, para contar el límite diario sin carreras. */
    public void bloquearMiembroEnFecha(String miembro, LocalDate fecha) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))")
                .param(miembro + "|" + fecha)
                .query((rs, n) -> null)
                .list();
    }

    public Optional<String> respuestaGuardada(String miembro, UUID clave, OffsetDateTime vigenteDesde) {
        return jdbc.sql("""
                        SELECT respuesta::text FROM clave_idempotencia
                        WHERE miembro = ? AND clave = ? AND creada_en > ?""")
                .param(miembro)
                .param(clave)
                .param(vigenteDesde)
                .query(String.class)
                .optional();
    }

    /** Guarda la respuesta; reemplaza solo una clave vencida. Devuelve falso si la clave sigue vigente. */
    public boolean guardarRespuesta(
            String miembro, UUID clave, String respuesta, OffsetDateTime ahora, OffsetDateTime vigenteDesde) {
        return jdbc.sql("""
                        INSERT INTO clave_idempotencia (miembro, clave, respuesta, creada_en)
                        VALUES (?, ?, ?::jsonb, ?)
                        ON CONFLICT (miembro, clave) DO UPDATE
                            SET respuesta = EXCLUDED.respuesta, creada_en = EXCLUDED.creada_en
                            WHERE clave_idempotencia.creada_en <= ?""")
                        .param(miembro)
                        .param(clave)
                        .param(respuesta)
                        .param(ahora)
                        .param(vigenteDesde)
                        .update()
                == 1;
    }

    public int reservasFuturasDelDia(String miembro, LocalDate fecha, LocalDateTime ahora) {
        return jdbc.sql("""
                        SELECT count(*) FROM reserva
                        WHERE miembro = ? AND estado = 'confirmed'
                          AND inicio >= ? AND inicio < ? AND inicio > ?""")
                .param(miembro)
                .param(fecha.atStartOfDay())
                .param(fecha.plusDays(1).atStartOfDay())
                .param(ahora)
                .query(Integer.class)
                .single();
    }

    public boolean existeCodigo(String codigo) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reserva WHERE codigo = ?)")
                .param(codigo)
                .query(Boolean.class)
                .single();
    }

    public void insertarReserva(ReservaFila r) {
        jdbc.sql("""
                        INSERT INTO reserva (codigo, sala_id, sede, inicio, fin, asunto, miembro, estado)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .param(r.codigo())
                .param(r.salaId())
                .param(r.sede())
                .param(r.inicio())
                .param(r.fin())
                .param(r.asunto())
                .param(r.miembro())
                .param(r.estado())
                .update();
    }

    public Optional<ReservaFila> reservaParaActualizar(String codigo) {
        return jdbc.sql("SELECT " + COLUMNAS_RESERVA + " FROM reserva WHERE codigo = ? FOR UPDATE")
                .param(codigo)
                .query(RepositorioReservas::reserva)
                .optional();
    }

    public void cancelar(String codigo, String por, OffsetDateTime en, String motivo) {
        jdbc.sql("""
                        UPDATE reserva SET estado = 'cancelled', cancelada_por = ?, cancelada_en = ?,
                            motivo_cancelacion = ?
                        WHERE codigo = ?""")
                .param(por)
                .param(en)
                .param(motivo)
                .param(codigo)
                .update();
        jdbc.sql("""
                        INSERT INTO auditoria_cancelacion (codigo_reserva, cancelada_por, cancelada_en, motivo)
                        VALUES (?, ?, ?, ?)""")
                .param(codigo)
                .param(por)
                .param(en)
                .param(motivo)
                .update();
    }

    public List<ReservaFila> futurasDelMiembro(String miembro, LocalDateTime ahora) {
        return jdbc.sql("SELECT " + COLUMNAS_RESERVA + " FROM reserva WHERE miembro = ? AND inicio > ? ORDER BY inicio")
                .param(miembro)
                .param(ahora)
                .query(RepositorioReservas::reserva)
                .list();
    }

    public List<ReservaFila> futurasDeLaSede(Sede sede, LocalDateTime ahora) {
        return jdbc.sql("SELECT " + COLUMNAS_RESERVA + " FROM reserva WHERE sede = ? AND inicio > ? ORDER BY inicio")
                .param(sede.valor())
                .param(ahora)
                .query(RepositorioReservas::reserva)
                .list();
    }

    private static SalaFila sala(ResultSet rs, int n) throws SQLException {
        Array equipamiento = rs.getArray("equipamiento");
        return new SalaFila(
                rs.getString("id"),
                rs.getString("sede"),
                rs.getString("nombre"),
                rs.getInt("capacidad"),
                Arrays.asList((String[]) equipamiento.getArray()));
    }

    private static ReservaFila reserva(ResultSet rs, int n) throws SQLException {
        return new ReservaFila(
                rs.getString("codigo"),
                rs.getString("sala_id"),
                rs.getString("sede"),
                rs.getObject("inicio", LocalDateTime.class),
                rs.getObject("fin", LocalDateTime.class),
                rs.getString("asunto"),
                rs.getString("miembro"),
                rs.getString("estado"),
                rs.getString("cancelada_por"),
                rs.getObject("cancelada_en", OffsetDateTime.class),
                rs.getString("motivo_cancelacion"));
    }
}

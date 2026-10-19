package northwind.reservas.dominio;

import java.util.Optional;

/**
 * Quién llama, leído del JWT ya validado (ADR-002, ADR-105).
 *
 * @param miembro `sub` del token
 * @param recepcion si el token lleva el rol `recepcion`
 * @param sede atributo `sede` del token, si trae una sede conocida
 */
public record Solicitante(String miembro, boolean recepcion, Optional<Sede> sede) {

    /** ADR-105: rol `recepcion` con `sede` igual a la sede indicada. */
    public boolean esRecepcionDe(Sede sedeDeLaSala) {
        return recepcion && sede.filter(sedeDeLaSala::equals).isPresent();
    }
}

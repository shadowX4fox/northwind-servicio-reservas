-- Servicio de Reservas — esquema de la Base de Datos de Reservas (arquitectura 1.1.0).
-- Solo el Servicio de Reservas lee y escribe estas tablas (ADR-003, INV-12).
-- Las horas de inicio y fin son hora local de las sedes (reservas.zona-horaria).

-- ADR-102: la restricción de exclusión necesita btree_gist para combinar igualdad de sala y rango.
-- TODO(architecture): privilegios del usuario PostgreSQL del servicio (DML frente a DDL de
-- migraciones) no documentados — security T12-E "Inyección que ejecuta DDL con el usuario del servicio".
-- Todo el SQL del servicio va con parámetros enlazados.
-- TODO(architecture): cifrado en reposo de la Base de Datos de Reservas y de sus respaldos no
-- documentado — security T8-T y T10-I; auditoría de acceso administrativo no documentada — T9-R.
-- Son controles de Plataforma Northwind sobre PostgreSQL 16, fuera de este esquema.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- TODO(architecture): la arquitectura no documenta cómo se cargan las 42 salas de las 3 sedes;
-- el contrato no tiene operación de alta. Parte: spec § 6 (es dueño de "Salas").
CREATE TABLE sala (
    id           text    PRIMARY KEY,
    sede         text    NOT NULL CHECK (sede IN ('centro', 'norte', 'puerto')),
    nombre       text    NOT NULL,
    capacidad    integer NOT NULL CHECK (capacidad > 0),
    equipamiento text[]  NOT NULL DEFAULT '{}'
        CHECK (equipamiento <@ ARRAY['screen', 'videoconference', 'whiteboard']::text[])
);

CREATE TABLE reserva (
    codigo             text         PRIMARY KEY CHECK (codigo ~ '^RS-[A-Za-z0-9]{8}$'),
    sala_id            text         NOT NULL REFERENCES sala (id),
    sede               text         NOT NULL,
    inicio             timestamp    NOT NULL,
    fin                timestamp    NOT NULL,
    asunto             varchar(80)  NOT NULL,
    miembro            text         NOT NULL,
    estado             text         NOT NULL CHECK (estado IN ('confirmed', 'cancelled')),
    cancelada_por      text,
    cancelada_en       timestamptz,
    motivo_cancelacion varchar(120),
    creada_en          timestamptz  NOT NULL DEFAULT now(),
    CHECK (fin > inicio),
    -- ADR-102, INV-03: nunca dos reservas confirmadas superpuestas para la misma sala.
    CONSTRAINT reserva_sin_superposicion
        EXCLUDE USING gist (sala_id WITH =, tsrange(inicio, fin) WITH &&) WHERE (estado = 'confirmed')
);

CREATE INDEX reserva_miembro_inicio ON reserva (miembro, inicio);
CREATE INDEX reserva_sede_inicio ON reserva (sede, inicio);

-- ADR-106, INV-08: quién, cuándo y por qué se cancela, escrito en la transacción de la cancelación.
CREATE TABLE auditoria_cancelacion (
    id             bigserial    PRIMARY KEY,
    codigo_reserva text         NOT NULL REFERENCES reserva (codigo),
    cancelada_por  text         NOT NULL,
    cancelada_en   timestamptz  NOT NULL,
    motivo         varchar(120)
);

-- ADR-103, INV-05: la clave y la respuesta guardada de la creación, 24 horas.
CREATE TABLE clave_idempotencia (
    miembro   text        NOT NULL,
    clave     uuid        NOT NULL,
    respuesta jsonb       NOT NULL,
    creada_en timestamptz NOT NULL,
    PRIMARY KEY (miembro, clave)
);

-- TODO(architecture): ADR-106 fija 24 meses de retención para reservas y auditoría y deja la purga
-- a la Base de Datos de Reservas, pero su calendario no está documentado; tampoco la purga de las
-- claves de idempotencia vencidas (ADR-103). Ninguna purga se programa aquí.

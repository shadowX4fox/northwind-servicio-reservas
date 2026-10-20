-- TDR-002, opción B: el Servicio de Reservas borra periódicamente las claves de idempotencia de más de
-- 24 horas (ADR-103); este índice sobre `creada_en` sirve a esa purga.
CREATE INDEX clave_idempotencia_creada_en ON clave_idempotencia (creada_en);

-- Contrato 1.10.0 (openapi.yaml 1.3.0): el asunto de la reserva admite hasta 120 caracteres.
ALTER TABLE reserva ALTER COLUMN asunto TYPE varchar(120);

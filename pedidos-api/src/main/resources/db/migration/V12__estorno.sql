-- Fase 7b: quando o dinheiro de um pedido pago e cancelado foi devolvido ao cliente (registrado pelo gerente).
ALTER TABLE pedido ADD COLUMN estornado_em timestamp(6) with time zone;

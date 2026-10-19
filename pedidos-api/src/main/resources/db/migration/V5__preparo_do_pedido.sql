-- Fase 3a: andamento do pedido na loja (cozinha, retirada, cancelamento), separado do status de pagamento.
ALTER TABLE pedido ADD COLUMN preparo varchar(20) NOT NULL DEFAULT 'NA_FILA';
ALTER TABLE pedido ADD CONSTRAINT pedido_preparo_check
    CHECK (preparo IN ('NA_FILA', 'PREPARANDO', 'PRONTO', 'RETIRADO', 'CANCELADO'));
-- O que já estava pago antes da cozinha existir já foi servido: não entra na fila de hoje.
UPDATE pedido SET preparo = 'RETIRADO' WHERE status = 'PAGO';
-- A fila da cozinha: pedidos PAGO da loja, por andamento, do mais antigo ao mais novo.
CREATE INDEX idx_pedido_cozinha ON pedido (loja_id, status, preparo, id);

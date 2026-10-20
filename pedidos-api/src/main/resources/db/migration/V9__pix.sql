-- Fase 5: cobrança Pix do pedido (criada pelo Pagamentos e devolvida pelo evento pagamentos-resultados com status AGUARDANDO).
ALTER TABLE pedido ADD COLUMN pix_copia_cola text;
ALTER TABLE pedido ADD COLUMN pix_qr_code text;
ALTER TABLE pedido ADD COLUMN pix_expira_em timestamp(6) with time zone;

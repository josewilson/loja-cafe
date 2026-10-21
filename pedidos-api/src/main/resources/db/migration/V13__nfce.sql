-- Fase 6b: NFC-e do pedido (status AUTORIZADA, REJEITADA ou CANCELADA). Tudo nulo até a primeira tentativa de emissão.
ALTER TABLE pedido ADD COLUMN nfce_status varchar(12);
ALTER TABLE pedido ADD COLUMN nfce_numero varchar(12);
ALTER TABLE pedido ADD COLUMN nfce_chave varchar(44);
ALTER TABLE pedido ADD COLUMN nfce_url varchar(300);
ALTER TABLE pedido ADD COLUMN nfce_mensagem varchar(240);
ALTER TABLE pedido ADD COLUMN nfce_emitida_em timestamp(6) with time zone;

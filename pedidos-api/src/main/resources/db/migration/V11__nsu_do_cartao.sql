-- Fase 7a: comprovante (NSU) do cartão aprovado na maquininha, informado pelo atendente (opcional).
ALTER TABLE pedido ADD COLUMN nsu varchar(30);

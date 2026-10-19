-- O banco passa a garantir sozinho o que antes dependia só do código.

-- Estoque nunca fica negativo, mesmo que alguém escreva direto no banco.
UPDATE produto SET estoque = 0 WHERE estoque IS NULL;
ALTER TABLE produto ADD CONSTRAINT produto_estoque_nao_negativo CHECK (estoque >= 0);
ALTER TABLE produto ALTER COLUMN estoque SET NOT NULL;

ALTER TABLE usuario ALTER COLUMN nome SET NOT NULL;
ALTER TABLE usuario ALTER COLUMN email SET NOT NULL;
ALTER TABLE usuario ALTER COLUMN senha_hash SET NOT NULL;
ALTER TABLE pedido ALTER COLUMN usuario_id SET NOT NULL;
ALTER TABLE pedido ALTER COLUMN status SET NOT NULL;
ALTER TABLE pedido ALTER COLUMN valor SET NOT NULL;
ALTER TABLE item_pedido ALTER COLUMN pedido_id SET NOT NULL;

-- Índices das consultas quentes: "meus pedidos" (a cada 2 s por cliente), itens de um pedido e limpeza de sessões.
CREATE INDEX idx_pedido_usuario_id ON pedido (usuario_id, id DESC);
CREATE INDEX idx_item_pedido_pedido_id ON item_pedido (pedido_id);
CREATE INDEX idx_item_pedido_extras_item ON item_pedido_extras (item_pedido_id);
CREATE INDEX idx_sessao_expira_em ON sessao (expira_em);

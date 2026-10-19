-- Migração ÚNICA para bancos criados antes do login e dos vários itens por pedido.
-- Rode DEPOIS de subir a nova pedidos-api uma vez (o Hibernate cria as tabelas novas):
--   docker exec -i loja-cafe-postgres-1 psql -U loja loja_cafe < infra/migracao-v2.sql
-- Os pedidos antigos passam para a conta "legado" (ninguém consegue entrar nela) e cada um vira 1 item.
-- Banco novo, criado já com a nova API, NÃO precisa disto. Se a coluna "item" não existe mais, já foi migrado.
BEGIN;

INSERT INTO usuario (nome, email, senha_hash)
SELECT 'Legado', 'legado@loja-cafe.local', '!' -- "!" não é um hash BCrypt: não há senha que entre
WHERE NOT EXISTS (SELECT 1 FROM usuario WHERE email = 'legado@loja-cafe.local');

INSERT INTO item_pedido (pedido_id, produto_id, item, quantidade, tamanho, preco_unitario, valor)
SELECT p.id, (SELECT pr.id FROM produto pr WHERE pr.nome = p.item), p.item, p.quantidade,
       COALESCE(p.tamanho, 'MEDIO'), COALESCE(p.preco_unitario, p.valor / NULLIF(p.quantidade, 0)), p.valor
FROM pedido p
WHERE p.item IS NOT NULL AND NOT EXISTS (SELECT 1 FROM item_pedido i WHERE i.pedido_id = p.id);

INSERT INTO item_pedido_extras (item_pedido_id, extras)
SELECT i.id, e.extras FROM pedido_extras e JOIN item_pedido i ON i.pedido_id = e.pedido_id;

UPDATE pedido SET usuario_id = (SELECT id FROM usuario WHERE email = 'legado@loja-cafe.local')
WHERE usuario_id IS NULL;

DROP TABLE pedido_extras;
ALTER TABLE pedido DROP COLUMN item, DROP COLUMN quantidade, DROP COLUMN tamanho, DROP COLUMN preco_unitario;

COMMIT;

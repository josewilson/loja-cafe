-- Cardápio inicial. Só insere se o id ainda não existe (pode rodar a cada partida).
INSERT INTO produto (id, nome, descricao, emoji, preco, estoque)
SELECT 1, 'Café coado', 'Filtrado na hora, grão da casa', '☕', 8.50, 50 WHERE NOT EXISTS (SELECT 1 FROM produto WHERE id = 1);
INSERT INTO produto (id, nome, descricao, emoji, preco, estoque)
SELECT 2, 'Cappuccino', 'Espresso, leite vaporizado e espuma cremosa', '🥛', 12.00, 50 WHERE NOT EXISTS (SELECT 1 FROM produto WHERE id = 2);
INSERT INTO produto (id, nome, descricao, emoji, preco, estoque)
SELECT 3, 'Pão de queijo', 'Quentinho, casquinha crocante', '🧀', 6.00, 50 WHERE NOT EXISTS (SELECT 1 FROM produto WHERE id = 3);
INSERT INTO produto (id, nome, descricao, emoji, preco, estoque)
SELECT 4, 'Bolo de cenoura', 'Com cobertura de chocolate', '🍰', 9.50, 50 WHERE NOT EXISTS (SELECT 1 FROM produto WHERE id = 4);
-- Produtos criados antes do estoque existir (coluna nova nasce vazia): começam com 50.
UPDATE produto SET estoque = 50 WHERE estoque IS NULL;

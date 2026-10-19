-- Cardápio inicial. Só insere se o id ainda não existe (pode rodar a cada partida).
INSERT INTO produto (id, nome, descricao, emoji, preco)
SELECT 1, 'Café coado', 'Filtrado na hora, grão da casa', '☕', 8.50 WHERE NOT EXISTS (SELECT 1 FROM produto WHERE id = 1);
INSERT INTO produto (id, nome, descricao, emoji, preco)
SELECT 2, 'Cappuccino', 'Espresso, leite vaporizado e espuma cremosa', '🥛', 12.00 WHERE NOT EXISTS (SELECT 1 FROM produto WHERE id = 2);
INSERT INTO produto (id, nome, descricao, emoji, preco)
SELECT 3, 'Pão de queijo', 'Quentinho, casquinha crocante', '🧀', 6.00 WHERE NOT EXISTS (SELECT 1 FROM produto WHERE id = 3);
INSERT INTO produto (id, nome, descricao, emoji, preco)
SELECT 4, 'Bolo de cenoura', 'Com cobertura de chocolate', '🍰', 9.50 WHERE NOT EXISTS (SELECT 1 FROM produto WHERE id = 4);

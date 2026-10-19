-- Cardápio da rede nos testes (H2). O estoque é por loja e cada teste cria a sua. Mesmos produtos da V2.
INSERT INTO produto (id, nome, descricao, emoji, preco, ativo) VALUES (1, 'Café coado', 'Filtrado na hora, grão da casa', '☕', 8.50, true);
INSERT INTO produto (id, nome, descricao, emoji, preco, ativo) VALUES (2, 'Cappuccino', 'Espresso, leite vaporizado e espuma cremosa', '🥛', 12.00, true);
INSERT INTO produto (id, nome, descricao, emoji, preco, ativo) VALUES (3, 'Pão de queijo', 'Quentinho, casquinha crocante', '🧀', 6.00, true);
INSERT INTO produto (id, nome, descricao, emoji, preco, ativo) VALUES (4, 'Bolo de cenoura', 'Com cobertura de chocolate', '🍰', 9.50, true);
-- Os produtos criados pelos testes começam em 100, como no banco de verdade.
ALTER TABLE produto ALTER COLUMN id RESTART WITH 100;

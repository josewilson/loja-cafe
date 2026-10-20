# Contrato da API de Pedidos (REST)

Base: `http://localhost:8081`. JSON em UTF-8. CORS liberado para `http://localhost:5173`.
Em produção a API fica atrás do Caddy em `/api` (a tela e a API no mesmo endereço, sem CORS); as rotas daqui continuam as mesmas, sem o prefixo.
O preço **nunca** vem do cliente: a API tem o cardápio e calcula o valor.
Loja física: não há entrega nem frete; o cliente retira no balcão. Quem usa o sistema é a **equipe**
(o atendente lança o pedido no balcão); não existe cadastro público.

## Rede de lojas e papéis
Cada usuário tem um **papel** e, exceto o `ADMIN`, pertence a **uma loja**. Pedidos, estoque e usuários são sempre de uma loja.
O cardápio e os preços são da rede (iguais em todas as lojas); o estoque é de cada loja.

| Papel | Pode |
|---|---|
| `ADMIN` | A rede toda: criar lojas e usuários de qualquer loja, ajustar estoque, ver pedidos e auditoria de todas as lojas |
| `GERENTE` | Na sua loja: lançar pedidos, criar/desativar `ATENDENTE` e `COZINHA`, ajustar estoque, ver auditoria, tocar a fila de preparo e **cancelar pedidos** |
| `ATENDENTE` | Na sua loja: lançar pedidos, ver os pedidos e marcar `RETIRADO` quando o cliente leva |
| `COZINHA` | Na sua loja: ver os pedidos, tocar a fila de preparo (`PREPARANDO`, `PRONTO`) |

Sem token: `401`. Papel sem permissão, ou dado de outra loja: `403`. Conta desativada: `401`.
O primeiro `ADMIN` é criado na primeira partida da API a partir de `ADMIN_EMAIL` e `ADMIN_PASSWORD` (`infra/.env`).

## Públicos (sem login)

### GET /opcoes
Tamanhos, extras e formas de pagamento aceitos. Resposta `200`:
```json
{ "tamanhos": [ { "codigo": "MEDIO", "nome": "Médio", "ajuste": 0.00, "fatorInsumo": 1.00 } ],
  "extras":   [ { "codigo": "CHANTILLY", "nome": "Chantilly", "preco": 2.00 } ],
  "formasPagamento": [ { "codigo": "CREDITO", "nome": "Cartão de crédito", "parcelasMax": 3 } ] }
```
| Tamanho | Ajuste no preço unitário |
|---|---|
| `PEQUENO` | −1,00 |
| `MEDIO` (padrão) | 0,00 |
| `GRANDE` | +3,00 |

| Extra | Preço |
|---|---|
| `LEITE_VEGETAL` | 2,00 |
| `CHANTILLY` | 2,00 |
| `CARAMELO` | 1,50 |
| `CANELA` | 0,50 |

| Forma de pagamento | `parcelasMax` | Como o Pagamentos decide (simulado) |
|---|---|---|
| `PIX` | 1 | Sem provedor de Pix (`PIX_PROVEDOR=automatico`, o padrão): aprova em ~1 s, sem limite. Com provedor: gera a cobrança, o pedido fica `AGUARDANDO_PAGAMENTO` com o campo `pix` até o cliente pagar (`PAGO`) ou a cobrança vencer (`RECUSADO`, motivo `pix expirado`) |
| `CREDITO` | 3 | ~3 s; recusa acima de R$ 100,00 |
| `DEBITO` | 1 | ~3 s; recusa acima de R$ 100,00 |
| `DINHEIRO` | 1 | Pagamento no balcão: aprova na hora |

### GET /saude
Diz se a API está de pé: o banco responde e o Kafka está acessível. Serve ao monitoramento e à verificação de saúde do Docker.
Não revela detalhes internos. `200` com `{ "status": "ok", "banco": "ok", "kafka": "ok" }`; se algo falhar, `503` com
`"status": "falha"` e `"falha"` no item que não respondeu. O resultado do Kafka vale por 10 s (não abre uma conexão nova a cada chamada).

## Login
O token é aleatório, vale 24 h e vai em `Authorization: Bearer <token>`. Senha de 8 a 72 caracteres (BCrypt).
Após 5 senhas erradas em 15 min (mesmo IP + e-mail): `429`.

### POST /auth/entrar
Corpo: `{ "email": "ana@exemplo.com", "senha": "segredo123" }`. `200`:
```json
{ "token": "...", "nome": "Ana", "papel": "ATENDENTE", "lojaId": 1, "lojaNome": "Matriz" }
```
(`lojaId` e `lojaNome` são `null` para o `ADMIN`.) E-mail ou senha errados: `401`, sem dizer qual dos dois.

### POST /auth/sair
Exige token. Encerra a sessão. `204`.

## Lojas, usuários e estoque

### GET /lojas · POST /lojas  (`ADMIN`)
`POST` corpo: `{ "nome": "Centro", "estoqueInicial": 20 }` (`nome` até 120, único; `estoqueInicial` opcional, de 0 a 10000, padrão 0,
vale para cada produto). `201` com `{ "id": 2, "nome": "Centro", "ativa": true }`. `GET` lista todas.

### GET /usuarios · POST /usuarios  (`ADMIN`, `GERENTE`)
`POST` corpo: `{ "nome": "Bia", "email": "bia@exemplo.com", "senha": "segredo123", "papel": "ATENDENTE", "lojaId": 1 }`.
- `ADMIN` cria qualquer papel; `lojaId` é obrigatório, exceto para `ADMIN`.
- `GERENTE` cria só `ATENDENTE` e `COZINHA` na própria loja (`lojaId` é opcional; se vier, tem que ser a dele).
- E-mail já usado: `409`. `201` com `{ "id", "nome", "email", "papel", "lojaId", "ativo" }` (nunca devolve a senha).
- `GET`: o `ADMIN` vê todos; o `GERENTE`, os da própria loja.

### PUT /usuarios/{id}/ativo  (`ADMIN`, `GERENTE`)
Corpo: `{ "ativo": false }`. Desativar encerra as sessões da pessoa. O `GERENTE` só mexe em `ATENDENTE`/`COZINHA` da própria loja.
Ninguém desativa a si mesmo. `200` com o usuário.

### PUT /lojas/{lojaId}/estoque/{produtoId}  (`ADMIN`, `GERENTE` da loja)
Corpo: `{ "quantidade": 40 }` (de 0 a 100000). Define o estoque do produto naquela loja. `200` com `{ "lojaId", "produtoId", "quantidade" }`.

### GET /auditoria?limite=N  (`ADMIN`, `GERENTE`)
Quem fez o quê e quando, do mais novo ao mais antigo (padrão 100, de 1 a 500). O `GERENTE` vê só a própria loja; o `ADMIN` vê tudo
(`?lojaId=` filtra). Resposta `200`:
```json
[ { "id": 9, "quando": "2026-10-17T16:05:00Z", "usuarioId": 3, "lojaId": 1, "acao": "PEDIDO_CRIADO", "entidade": "Pedido", "entidadeId": "12", "detalhe": "valor 35.00" } ]
```
Ações: `LOGIN`, `LOGIN_FALHOU`, `LOJA_CRIADA`, `USUARIO_CRIADO`, `USUARIO_ATIVO_ALTERADO`, `ESTOQUE_AJUSTADO`, `PEDIDO_CRIADO`,
`PREPARO_ALTERADO`, `PEDIDO_CANCELADO`, `CAIXA_FECHADO`, `PRODUTO_CRIADO`, `PRODUTO_ALTERADO`, `INSUMO_CRIADO`, `INSUMO_ALTERADO`, `FICHA_ALTERADA`, `INSUMO_AJUSTADO`
(as cinco primeiras são da rede, sem loja: só o `ADMIN` as vê na auditoria; `INSUMO_AJUSTADO` é da loja), `PEDIDOS_REENFILEIRADOS` (da rede).

## Cardápio e pedidos (exigem token)

### GET /produtos
Cardápio da rede (só os produtos **ativos**), na ordem de `id`, com o `estoque` **da loja do usuário**. O `ADMIN` (sem loja) informa `?lojaId=`. Resposta `200`:
```json
[ { "id": 1, "nome": "Café coado", "descricao": "Filtrado na hora, grão da casa", "emoji": "☕", "preco": 8.50, "estoque": 50 } ]
```

### POST /pedidos  (`ATENDENTE`, `GERENTE`)
Corpo:
```json
{ "cliente": "Ana", "formaPagamento": "CREDITO", "parcelas": 2, "observacao": "sem açúcar",
  "itens": [ { "produtoId": 2, "quantidade": 2, "tamanho": "GRANDE", "extras": ["CHANTILLY", "CANELA"] } ] }
```
- Obrigatórios: `formaPagamento`; `itens` (de 1 a 10); em cada item, `produtoId` (existente e ativo em `/produtos`; produto desativado: `400`) e `quantidade` (1 a 50).
- Opcionais: `cliente` (nome dito no balcão, até 60; padrão `"Balcão"`), `parcelas` (1 a 3, só com `CREDITO`; padrão 1),
  `observacao` (até 140), e por item `tamanho` (padrão `MEDIO`) e `extras` (até 5; repetido conta uma vez).
- O pedido nasce na loja de quem lança (`atendente` = o usuário). Campos que não existem aqui (por exemplo `precoUnitario`) são ignorados.
- O servidor calcula `precoUnitario = preço do produto + ajuste do tamanho + soma dos extras`, o `valor` do item e `valor` do pedido = soma dos itens.
- Estoque: cada item abate `quantidade` do estoque **da loja**. Sem estoque suficiente: `400` dizendo qual produto.
  Se o pagamento for `RECUSADO`, o estoque volta (uma única vez).
- Resposta `201 Created` com o pedido (formato abaixo). Inválido: `400`, com `message` e, em erro de validação,
  `errors` listando `field` e `defaultMessage` de cada campo que falhou.

### GET /pedidos?limite=N  (todos os papéis)
Pedidos **da loja do usuário**, **mais novo primeiro** (padrão 100, de 1 a 200). O `ADMIN` vê todas as lojas (`?lojaId=` filtra). Resposta `200`:
```json
[
  { "id": 12, "lojaId": 1, "cliente": "Ana", "atendente": "Bia", "formaPagamento": "CREDITO", "parcelas": 2, "observacao": "sem açúcar",
    "itens": [ { "produtoId": 2, "item": "Cappuccino", "quantidade": 2, "tamanho": "GRANDE",
                 "extras": ["CHANTILLY", "CANELA"], "precoUnitario": 17.50, "valor": 35.00 } ],
    "valor": 35.00, "status": "AGUARDANDO_PAGAMENTO", "preparo": "NA_FILA", "motivo": null, "processadoPor": null,
    "pix": null, "criadoEm": "2026-10-17T16:05:00Z" }
]
```
`item` é o nome do produto no momento do pedido.
`pix` é `null`, exceto em pedido `PIX` que **ainda aguarda o pagamento** e já tem cobrança: então vem
`{ "copiaECola": "0002012...", "qrCode": "<PNG em base64>", "expiraEm": "2026-10-17T16:15:00Z" }` (a tela mostra o QR). Quando o pedido é concluído, volta a `null`.

## Cardápio da rede (`ADMIN`)
Quem edita o cardápio é só o `ADMIN`, porque ele é da rede: o preço muda em todas as lojas. O gerente cuida do estoque da própria loja.

### GET /cardapio
Todos os produtos, **inclusive os desativados**, na ordem de `id`. Sem estoque (o estoque é de cada loja). Resposta `200`:
```json
[ { "id": 1, "nome": "Café coado", "descricao": "Filtrado na hora, grão da casa", "emoji": "☕", "preco": 8.50, "ativo": true } ]
```

### POST /cardapio
Corpo: `{ "nome": "Pão na chapa", "descricao": "Com manteiga", "emoji": "🥖", "preco": 7.00 }`.
- `nome` obrigatório, até 60, **único** sem diferenciar maiúsculas (repetido: `409`); `descricao` opcional, até 140; `emoji` obrigatório, até 16 caracteres;
  `preco` obrigatório, maior que 0 e até 10000,00, com no máximo 2 casas decimais.
- `201` com o produto (`ativo: true`). Nasce com **estoque 0 em todas as lojas**: cada gerente ajusta o da sua (`PUT /lojas/{id}/estoque/{produtoId}`).

### PUT /cardapio/{id}
Mesmo corpo do `POST` mais `"ativo": true|false`. `200` com o produto. Inexistente: `404`; nome de outro produto: `409`.
- O **preço novo vale só para pedidos novos**: pedidos já feitos guardam o preço que usaram.
- Produto **desativado** some do `GET /produtos` (balcão) e não aceita pedido novo, mas continua nos pedidos antigos e nos relatórios.

## Insumos e ficha técnica
Insumo = matéria-prima da rede (`Grão de café`, `Leite`, `Copo 300 ml`), com **unidade fixa** (`G`, `ML` ou `UN`; não muda depois de criado).
O catálogo e as fichas são da rede (só o `ADMIN` edita); o **estoque e o mínimo são de cada loja**. A quantidade usa até 3 casas decimais
e **pode ficar negativa**: a venda nunca é bloqueada por falta de insumo, o sistema só avisa.

**Ficha técnica** = quanto de cada insumo gasta **1 unidade** de um produto (escrita para o tamanho `MEDIO`) ou de um extra.
Consumo de um item do pedido = `quantidade × fatorInsumo do tamanho × (ficha do produto + ficha de cada extra)`.
`fatorInsumo`: `PEQUENO` 0,75 · `MEDIO` 1,00 · `GRANDE` 1,50 (também vem em `GET /opcoes`). Produto sem ficha não gasta insumo.

### Baixa e devolução
- Ao **lançar o pedido**, baixa o estoque de insumos **da loja de quem lançou** e guarda o consumo daquele pedido (a ficha pode mudar depois).
- Pedido **recusado** devolve os insumos, uma única vez.
- Pedido **cancelado** devolve só se ainda estava `NA_FILA`. Se já estava `PREPARANDO`, os insumos foram usados e contam como **perda** (não voltam).
  (O estoque de produtos prontos continua voltando nos dois casos.)

### GET /insumos  (`ADMIN`, `GERENTE`)
Catálogo com o estoque **da loja do usuário** (o `ADMIN` informa `?lojaId=`). Resposta `200`:
```json
[ { "id": 1, "nome": "Leite", "unidade": "ML", "ativo": true, "quantidade": 4200.000, "minimo": 1000.000, "abaixoDoMinimo": false } ]
```
`abaixoDoMinimo` = `quantidade ≤ minimo`.

### POST /insumos  (`ADMIN`)
`{ "nome": "Grão de café", "unidade": "G" }` (`nome` até 60, único sem diferenciar maiúsculas: repetido `409`; `unidade` `G`, `ML` ou `UN`).
`201`. Nasce com estoque 0 e mínimo 0 em todas as lojas. Lojas criadas depois também ganham a linha.

### PUT /insumos/{id}  (`ADMIN`)
`{ "nome": "Leite integral", "ativo": true }`. A unidade não muda. `200`. Insumo desativado deixa de ser consumido e de alertar.

### PUT /lojas/{lojaId}/insumos/{insumoId}  (`ADMIN`, `GERENTE` da loja)
`{ "quantidade": 5000, "minimo": 1000 }`. É a **contagem** do estoque (de 0 a 999999999, até 3 casas); `minimo` é opcional (de 0 a 999999999;
se faltar, mantém o atual). Outra loja: `403`. Insumo ou loja inexistente: `404`. `200` com `{ "lojaId", "insumoId", "quantidade", "minimo" }`.

### GET /insumos/alertas  (`ADMIN`, `GERENTE`)
Insumos ativos da loja com `quantidade ≤ minimo` (o `ADMIN` informa `?lojaId=`), os que **acabaram** primeiro. `200`:
```json
[ { "insumoId": 1, "nome": "Leite", "unidade": "ML", "quantidade": -150.000, "minimo": 1000.000, "situacao": "ACABOU" } ]
```
`situacao`: `ACABOU` (`quantidade ≤ 0`) ou `BAIXO`.

### Fichas técnicas
**GET /fichas/produtos/{id}** · **GET /fichas/extras/{codigo}**  (`ADMIN`, `GERENTE`) — `200`:
```json
{ "itens": [ { "insumoId": 1, "nome": "Leite", "unidade": "ML", "quantidade": 150.000 } ] }
```
**PUT /fichas/produtos/{id}** · **PUT /fichas/extras/{codigo}**  (`ADMIN`) — **substitui a ficha inteira**. Corpo igual ao da resposta, só com
`insumoId` e `quantidade` (maior que 0, até 100000, 3 casas). Até 30 itens, sem insumo repetido, todos existentes (senão `400`).
Lista vazia = não gasta nada. Produto ou extra inexistente: `404`. `200` com a ficha.

### POST /pedidos/reenfileirar?minutos=N  (`ADMIN`)
Reenvia ao Pagamentos os pedidos que **ainda aguardam pagamento** há mais de `N` minutos (padrão 10; de 0 a 10080; `0` = todos os que aguardam).
Serve depois de restaurar um backup (o Kafka não entra no backup) ou de uma falha longa do Pagamentos. Reenvia o evento `pedidos-criados`
de cada um (até 500 por chamada, do mais antigo ao mais novo; chame de novo se `reenviados` for 500). É seguro repetir: o primeiro resultado
que chegar vale e os seguintes são ignorados. `N` fora da faixa: `400`. Resposta `200`: `{ "reenviados": 3, "minutos": 10 }`.
Grava `PEDIDOS_REENFILEIRADOS` na auditoria (da rede, sem loja).

## Cozinha, retirada e cancelamento
`preparo` é o andamento na loja e é **independente** do `status` (que é só o pagamento). Todo pedido nasce `NA_FILA`.

| `preparo` | Quando |
|---|---|
| `NA_FILA` | Esperando a cozinha |
| `PREPARANDO` | A cozinha começou |
| `PRONTO` | Pode retirar no balcão |
| `RETIRADO` | O cliente levou (fim) |
| `CANCELADO` | Cancelado pelo gerente (fim) |

### GET /cozinha?limite=N  (todos os papéis)
A fila da loja: só pedidos **`PAGO`** com `preparo` em `NA_FILA`, `PREPARANDO` ou `PRONTO`, do **mais antigo para o mais novo**
(padrão 100, de 1 a 200). O `ADMIN` informa `?lojaId=`. Mesmo formato de `GET /pedidos`.

### PUT /pedidos/{id}/preparo  (`COZINHA`, `GERENTE`, `ATENDENTE`)
Corpo: `{ "preparo": "PREPARANDO" }`. Só avança um passo por vez, e só em pedido `PAGO` da própria loja:
`NA_FILA → PREPARANDO → PRONTO → RETIRADO`.
- `PREPARANDO` e `PRONTO`: `COZINHA` ou `GERENTE`. `RETIRADO`: `ATENDENTE` ou `GERENTE`. Outro papel: `403`.
- Pedido ainda não pago, salto de etapa ou estado que já mudou: `409`. Pedido de outra loja: `403`. Inexistente: `404`.
- `200` com o pedido. Grava `PREPARO_ALTERADO` na auditoria.

### PUT /pedidos/{id}/cancelar  (`GERENTE`)
Corpo opcional: `{ "motivo": "cliente desistiu" }` (até 140, vai para a auditoria). Só cancela enquanto o `preparo` é `NA_FILA` ou `PREPARANDO`
e o pagamento não foi `RECUSADO`. Devolve o estoque da loja (uma única vez) e grava `PEDIDO_CANCELADO`.
Os **insumos** só voltam se o pedido ainda estava `NA_FILA`; em `PREPARANDO` contam como perda (veja *Insumos e ficha técnica*).
Já `PRONTO`, `RETIRADO`, `CANCELADO`, `RECUSADO` ou já contado num fechamento de caixa: `409`. Outra loja: `403`. `200` com o pedido.

## Painel do gerente: vendas e caixa
"Venda" = pedido `PAGO` e não `CANCELADO`. Valores em reais. `forma` = `PIX`, `CREDITO`, `DEBITO` ou `DINHEIRO`.

### GET /relatorios/vendas?data=AAAA-MM-DD  (`ADMIN`, `GERENTE`)
Vendas de um dia da loja (padrão: hoje; dia = de 00:00 a 24:00 no fuso de São Paulo, pela hora em que o pedido foi criado).
O `ADMIN` informa `?lojaId=`. Data inválida: `400`. Resposta `200`:
```json
{ "data": "2026-10-17", "lojaId": 1, "pedidos": 12, "total": 340.50, "ticketMedio": 28.38,
  "porForma": [ { "forma": "PIX", "pedidos": 7, "total": 210.00 } ],
  "maisVendidos": [ { "item": "Cappuccino", "quantidade": 15, "total": 225.00 } ],
  "recusados": 1, "cancelados": 2, "aguardando": 0 }
```
`maisVendidos` traz até 5 itens, do que mais saiu para o que menos saiu. `ticketMedio` é `0` sem vendas.

### Fechamento de caixa (por turno)
O caixa é fechado quando o turno acaba. Cada pedido vendido entra em **um único** fechamento (o primeiro depois que foi pago),
então nada se perde nem conta duas vezes, mesmo que o pagamento aprove segundos depois do fechamento.
Pedido que já entrou num fechamento **não pode mais ser cancelado**.

**GET /caixa/resumo**  (`GERENTE`; `ADMIN` com `?lojaId=`) — o turno aberto, ou seja, o que ainda não foi fechado:
```json
{ "lojaId": 1, "desde": "2026-10-17T12:00:00Z", "pedidos": 8, "total": 210.00,
  "porForma": [ { "forma": "DINHEIRO", "pedidos": 2, "total": 25.00 } ], "aguardando": 0 }
```
(`desde` é o fim do último fechamento, ou `null` se a loja nunca fechou.) `aguardando` avisa de pedidos ainda sem resposta do pagamento:
eles entram no próximo fechamento depois de pagos.

**POST /caixa/fechamentos**  (`GERENTE`) — fecha o turno. Corpo: `{ "dinheiroContado": 25.00, "observacao": "troco ok" }`
(`dinheiroContado` obrigatório, de 0 a 10000000; `observacao` até 140). Calcula o que era esperado em dinheiro (`DINHEIRO` vendido)
e a `diferenca = dinheiroContado − dinheiroEsperado` (negativa = faltou dinheiro). `201`:
```json
{ "id": 5, "lojaId": 1, "usuarioId": 3, "desde": "2026-10-17T12:00:00Z", "ate": "2026-10-17T20:00:00Z",
  "pedidos": 8, "total": 210.00,
  "porForma": [ { "forma": "DINHEIRO", "pedidos": 2, "total": 25.00 } ],
  "dinheiroEsperado": 25.00, "dinheiroContado": 24.00, "diferenca": -1.00,
  "observacao": "troco ok", "criadoEm": "2026-10-17T20:00:00Z" }
```
**GET /caixa/fechamentos?limite=N**  (`ADMIN`, `GERENTE`) — histórico, do mais novo ao mais antigo (padrão 50, de 1 a 200).

## Status do pedido
| Status | Quando |
|---|---|
| `AGUARDANDO_PAGAMENTO` | Pedido gravado, pagamento ainda não respondeu |
| `PAGO` | Pagamento aprovado |
| `RECUSADO` | Pagamento recusado (`motivo` explica) |

`processadoPor` = nome da instância de Pagamentos que decidiu (ex.: `caixa-1`); `null` enquanto aguarda.

## Cabeçalhos de segurança
Toda resposta leva `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`
e `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`.

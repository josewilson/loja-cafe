# Contrato da API de Pedidos (REST)

Base: `http://localhost:8081`. JSON em UTF-8. CORS liberado para `http://localhost:5173`.
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
{ "tamanhos": [ { "codigo": "MEDIO", "nome": "Médio", "ajuste": 0.00 } ],
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
| `PIX` | 1 | Aprova em ~1 s, sem limite de valor |
| `CREDITO` | 3 | ~3 s; recusa acima de R$ 100,00 |
| `DEBITO` | 1 | ~3 s; recusa acima de R$ 100,00 |
| `DINHEIRO` | 1 | Pagamento no balcão: aprova na hora |

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
`PREPARO_ALTERADO`, `PEDIDO_CANCELADO`.

## Cardápio e pedidos (exigem token)

### GET /produtos
Cardápio da rede, na ordem de `id`, com o `estoque` **da loja do usuário**. O `ADMIN` (sem loja) informa `?lojaId=`. Resposta `200`:
```json
[ { "id": 1, "nome": "Café coado", "descricao": "Filtrado na hora, grão da casa", "emoji": "☕", "preco": 8.50, "estoque": 50 } ]
```

### POST /pedidos  (`ATENDENTE`, `GERENTE`)
Corpo:
```json
{ "cliente": "Ana", "formaPagamento": "CREDITO", "parcelas": 2, "observacao": "sem açúcar",
  "itens": [ { "produtoId": 2, "quantidade": 2, "tamanho": "GRANDE", "extras": ["CHANTILLY", "CANELA"] } ] }
```
- Obrigatórios: `formaPagamento`; `itens` (de 1 a 10); em cada item, `produtoId` (existente em `/produtos`) e `quantidade` (1 a 50).
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
    "criadoEm": "2026-10-17T16:05:00Z" }
]
```
`item` é o nome do produto no momento do pedido.

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
Já `PRONTO`, `RETIRADO`, `CANCELADO` ou `RECUSADO`: `409`. Outra loja: `403`. `200` com o pedido.

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

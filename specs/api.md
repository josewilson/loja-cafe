# Contrato da API de Pedidos (REST)

Base: `http://localhost:8081`. JSON em UTF-8. CORS liberado para `http://localhost:5173`.
O preço **nunca** vem do cliente: a API tem o cardápio e calcula o valor.
Loja física: não há entrega nem frete; o cliente retira no balcão.

## Públicos (sem login)

### GET /produtos
Cardápio, na ordem de `id`. `estoque` = unidades disponíveis. Resposta `200`:
```json
[ { "id": 1, "nome": "Café coado", "descricao": "Filtrado na hora, grão da casa", "emoji": "☕", "preco": 8.50, "estoque": 50 } ]
```

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
Senha de 8 a 72 caracteres, guardada com BCrypt. O token é aleatório, vale 24 h e vai em
`Authorization: Bearer <token>`.

### POST /auth/registrar
Corpo: `{ "nome": "Ana", "email": "ana@exemplo.com", "senha": "segredo123" }`
(`nome` até 60, `email` válido até 120). `201` com `{ "token": "...", "nome": "Ana" }`.
E-mail já cadastrado: `409`. Inválido: `400`.

### POST /auth/entrar
Corpo: `{ "email": "ana@exemplo.com", "senha": "segredo123" }`. `200` com `{ "token": "...", "nome": "Ana" }`.
E-mail ou senha errados: `401`, sem dizer qual dos dois.

## Pedidos (exigem token; sem ele, `401`)

### POST /pedidos
Corpo:
```json
{ "formaPagamento": "CREDITO", "parcelas": 2, "observacao": "sem açúcar",
  "itens": [ { "produtoId": 2, "quantidade": 2, "tamanho": "GRANDE", "extras": ["CHANTILLY", "CANELA"] } ] }
```
- Obrigatórios: `formaPagamento`; `itens` (de 1 a 10); em cada item, `produtoId` (existente em `/produtos`) e `quantidade` (1 a 50).
- Opcionais: `parcelas` (1 a 3, só com `CREDITO`; padrão 1), `observacao` (até 140), e por item `tamanho` (padrão `MEDIO`) e `extras` (até 5; repetido conta uma vez).
- O cliente do pedido é a conta logada. Campos que não existem aqui (por exemplo `precoUnitario`) são ignorados.
- O servidor calcula `precoUnitario = preço do produto + ajuste do tamanho + soma dos extras`, o `valor` do item e `valor` do pedido = soma dos itens.
- Estoque: cada item abate `quantidade` do estoque do produto. Sem estoque suficiente: `400` dizendo qual produto.
  Se o pagamento for `RECUSADO`, o estoque volta (uma única vez).
- Resposta `201 Created` com o pedido (formato abaixo). Inválido: `400`, com `message` e, em erro de validação,
  `errors` listando `field` e `defaultMessage` de cada campo que falhou.

### GET /pedidos
Lista **os pedidos da conta logada**, **mais novo primeiro**. `?limite=N` (padrão 100, de 1 a 200). Resposta `200`:
```json
[
  { "id": 12, "cliente": "Ana", "formaPagamento": "CREDITO", "parcelas": 2, "observacao": "sem açúcar",
    "itens": [ { "produtoId": 2, "item": "Cappuccino", "quantidade": 2, "tamanho": "GRANDE",
                 "extras": ["CHANTILLY", "CANELA"], "precoUnitario": 17.50, "valor": 35.00 } ],
    "valor": 35.00, "status": "AGUARDANDO_PAGAMENTO", "motivo": null, "processadoPor": null,
    "criadoEm": "2026-10-17T16:05:00Z" }
]
```
`item` é o nome do produto no momento do pedido.

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

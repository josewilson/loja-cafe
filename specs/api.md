# Contrato da API de Pedidos (REST)

Base: `http://localhost:8081`. JSON em UTF-8. CORS liberado para `http://localhost:5173`.
O preço **nunca** vem do cliente: a API tem o cardápio e calcula o valor.

## GET /produtos
Cardápio, na ordem de `id`. Resposta `200`:
```json
[ { "id": 1, "nome": "Café coado", "descricao": "Filtrado na hora, grão da casa", "emoji": "☕", "preco": 8.50 } ]
```

## GET /opcoes
Tamanhos e extras aceitos no pedido. Resposta `200`:
```json
{ "tamanhos": [ { "codigo": "MEDIO", "nome": "Médio", "ajuste": 0.00 } ],
  "extras":   [ { "codigo": "CHANTILLY", "nome": "Chantilly", "preco": 2.00 } ] }
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

## POST /pedidos
Corpo:
```json
{ "cliente": "Ana", "produtoId": 2, "quantidade": 2, "tamanho": "GRANDE",
  "extras": ["CHANTILLY", "CANELA"], "observacao": "sem açúcar" }
```
- Obrigatórios: `cliente` (até 60 caracteres, não vazio), `produtoId` (existente em `/produtos`), `quantidade` (1 a 50).
- Opcionais: `tamanho` (padrão `MEDIO`), `extras` (até 5; repetido conta uma vez), `observacao` (até 140 caracteres).
- Campos que não existem aqui (por exemplo `precoUnitario`) são ignorados.
- O servidor calcula `precoUnitario = preço do produto + ajuste do tamanho + soma dos extras` e `valor = quantidade * precoUnitario`.
- Resposta `201 Created` com o pedido (formato abaixo). Inválido: `400`, com `message` e, em erro de validação,
  `errors` listando `field` e `defaultMessage` de cada campo que falhou.

## GET /pedidos
Lista os pedidos, **mais novo primeiro**. `?limite=N` (padrão 100, de 1 a 200). Resposta `200`:
```json
[
  { "id": 12, "cliente": "Ana", "item": "Cappuccino", "quantidade": 2, "tamanho": "GRANDE",
    "extras": ["CHANTILLY", "CANELA"], "observacao": "sem açúcar",
    "precoUnitario": 17.50, "valor": 35.00,
    "status": "AGUARDANDO_PAGAMENTO", "motivo": null, "processadoPor": null,
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

# Contrato da API de Pedidos (REST)

Base: `http://localhost:8081`. JSON em UTF-8. CORS liberado para `http://localhost:5173`.

## POST /pedidos
Corpo:
```json
{ "cliente": "Ana", "item": "Café coado", "quantidade": 2, "precoUnitario": 8.50 }
```
- `cliente` e `item` obrigatórios; `quantidade` de 1 a 50; `precoUnitario` maior que zero.
- O servidor calcula `valor = quantidade * precoUnitario`.
- Resposta `201 Created` com o pedido (formato abaixo). Inválido: `400`.

## GET /pedidos
Lista todos os pedidos, **mais novo primeiro**. Resposta `200`:
```json
[
  { "id": 12, "cliente": "Ana", "item": "Café coado", "quantidade": 2, "valor": 17.00,
    "status": "AGUARDANDO_PAGAMENTO", "motivo": null, "processadoPor": null,
    "criadoEm": "2026-10-17T16:05:00Z" }
]
```

## Status do pedido
| Status | Quando |
|---|---|
| `AGUARDANDO_PAGAMENTO` | Pedido gravado, pagamento ainda não respondeu |
| `PAGO` | Pagamento aprovado |
| `RECUSADO` | Pagamento recusado (`motivo` explica) |

`processadoPor` = nome da instância de Pagamentos que decidiu (ex.: `caixa-1`); `null` enquanto aguarda.

# Contrato dos eventos (Kafka) · usado a partir do Ato 3

Broker: `localhost:9092`. Valor em **JSON texto (UTF-8)**; chave = `pedidoId` como texto.
Os dois lados (Java e Python) leem e escrevem String e convertem com a biblioteca JSON da linguagem.
Não use serializador que grave tipo Java em header.
Produtor em Python (confluent-kafka) usa `"partitioner": "murmur2_random"`, o mesmo cálculo
do cliente Java: a mesma chave cai na mesma partição, venha de onde vier.

## Tópico `pedidos-criados` (3 partições)
Quem publica: `pedidos-api`, logo depois de gravar o pedido.
```json
{ "versao": 1, "pedidoId": 12, "cliente": "Ana", "item": "Café coado",
  "quantidade": 2, "valor": 17.00, "criadoEm": "2026-10-17T16:05:00Z" }
```

## Tópico `pagamentos-resultados` (3 partições)
Quem publica: `pagamentos-py`, depois de decidir.
```json
{ "versao": 1, "pedidoId": 12, "status": "APROVADO", "motivo": null,
  "instancia": "caixa-1", "processadoEm": "2026-10-17T16:05:03Z" }
```
`status`: `APROVADO` ou `RECUSADO`.

## Grupos de consumidores
| Grupo | Lê | Quem |
|---|---|---|
| `pagamentos` | `pedidos-criados` | pagamentos-py (1 ou mais instâncias) |
| `pedidos-api` | `pagamentos-resultados` | pedidos-api |
| `analytics` | os dois tópicos | analytics-py |

Todos com `auto.offset.reset = earliest`: grupo que nunca leu começa do início do tópico.

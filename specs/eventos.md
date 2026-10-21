# Contrato dos eventos (Kafka) · usado a partir do Ato 3

Broker: `localhost:9092` em desenvolvimento e `kafka:19092` dentro do Docker de produção. **O broker exige login**: `security.protocol=SASL_PLAINTEXT`,
`sasl.mechanism=PLAIN`, usuário e senha em `KAFKA_USER` e `KAFKA_PASSWORD` (`infra/.env` ou `deploy/.env`). Cliente sem login não conecta.
Valor em **JSON texto (UTF-8)**; chave = `pedidoId` como texto.
Os dois lados (Java e Python) leem e escrevem String e convertem com a biblioteca JSON da linguagem.
Não use serializador que grave tipo Java em header.
Produtor em Python (confluent-kafka) usa `"partitioner": "murmur2_random"`, o mesmo cálculo
do cliente Java: a mesma chave cai na mesma partição, venha de onde vier.

## Tópico `pedidos-criados` (3 partições)
Quem publica: `pedidos-api`, logo depois de gravar o pedido.
```json
{ "versao": 3, "pedidoId": 12, "lojaId": 1, "cliente": "Ana",
  "itens": [ { "item": "Café coado", "quantidade": 2 } ],
  "valor": 17.00, "formaPagamento": "PIX", "parcelas": 1, "criadoEm": "2026-10-17T16:05:00Z" }
```
`valor` = total do pedido. `lojaId` = a loja que vendeu. `formaPagamento`: `PIX`, `CREDITO`, `DEBITO` ou `DINHEIRO`.
**Versão 2** (eventos antigos no tópico): igual, sem `lojaId`. **Versão 1**: `"item"` e `"quantidade"` soltos, sem `itens`
nem `formaPagamento`. Quem lê aceita as três; sem `formaPagamento`, vale a regra do cartão de crédito.
**Reenvio:** `POST /pedidos/reenfileirar` (veja `specs/api.md`) publica este evento de novo para pedidos que ainda aguardam pagamento.
Quem lê já ignora a repetição: o primeiro resultado vale.

## Tópico `pagamentos-resultados` (3 partições)
Quem publica: `pagamentos-py`, depois de decidir.
```json
{ "versao": 1, "pedidoId": 12, "status": "APROVADO", "motivo": null,
  "instancia": "caixa-1", "processadoEm": "2026-10-17T16:05:03Z", "pix": null }
```
`status`: `APROVADO`, `RECUSADO` ou `AGUARDANDO` (e, na Fase 7c, `ESTORNADO` e `ESTORNO_FALHOU`: veja `pagamentos-estornos`).
- `AGUARDANDO` (Fase 5) **não é uma decisão**: o Pagamentos criou a cobrança Pix e o cliente ainda não pagou. Só acontece com `formaPagamento = PIX`
  quando o provedor de Pix está ligado (`PIX_PROVEDOR` diferente de `automatico`, veja `specs/pagamentos.md`). O campo `pix` vem preenchido só neste caso:
  `"pix": { "copiaECola": "0002012...", "qrCode": "<PNG em base64>", "expiraEm": "2026-10-17T16:15:00Z" }`; nos demais é `null`.
- Depois do `AGUARDANDO` vem **um** `APROVADO` (Pix pago) ou `RECUSADO` (`"motivo": "pix expirado"` ou `"pix indisponível no momento"`).
  Quem lê trata `AGUARDANDO` como informação e **não** como o resultado do pedido; o primeiro `APROVADO`/`RECUSADO` é que vale.

## Tópico `pagamentos-estornos` (3 partições) · Fase 7c
Quem publica: `pedidos-api`, quando o gerente cancela um pedido `PIX` que já estava `PAGO`. Quem lê: `pagamentos-py` (grupo `pagamentos`).
```json
{ "versao": 1, "pedidoId": 12, "valor": 17.00 }
```
O Pagamentos pede a devolução ao provedor do Pix e responde em `pagamentos-resultados` com o status `ESTORNADO` (devolvido) ou `ESTORNO_FALHOU`
(`motivo` preenchido). Nenhum dos dois é decisão de pagamento: a API só marca `estornadoEm` no `ESTORNADO` (e ignora se o pedido não estiver pago e cancelado)
e, no `ESTORNO_FALHOU`, só registra no log, e o gerente usa `PUT /pedidos/{id}/estorno`. Com `PIX_PROVEDOR=automatico` o Pagamentos não responde (estorno sempre à mão).
Se o pedido de estorno não sair (Kafka fora), o cancelamento vale e o gerente registra à mão.

## Grupos de consumidores
| Grupo | Lê | Quem |
|---|---|---|
| `pagamentos` | `pedidos-criados`, `pagamentos-estornos` | pagamentos-py (1 ou mais instâncias) |
| `pedidos-api` | `pagamentos-resultados` | pedidos-api |
| `analytics` | os dois tópicos | analytics-py |

Todos com `auto.offset.reset = earliest`: grupo que nunca leu começa do início do tópico.

## Retenção
As mensagens ficam **7 dias** no Kafka (o padrão do Kafka; fixo em `deploy/compose.prod.yml`). "Quem chega depois lê tudo" vale dentro dessa janela:
o Postgres é a fonte da verdade dos pedidos, e o Kafka não entra no backup.

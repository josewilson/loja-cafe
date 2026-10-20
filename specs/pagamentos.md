# Contrato do serviço de Pagamentos (simulado)

Configuração por variável de ambiente (o serviço também lê `infra/.env` sozinho, se o arquivo existir):

| Variável | Padrão | Para quê |
|---|---|---|
| `INSTANCIA` | `caixa-1` | Nome desta instância (vai no campo `instancia` dos resultados) |
| `PORTA` | `5001` | Porta HTTP |
| `HOST` | `127.0.0.1` | Interface de rede (no Docker é `0.0.0.0`, e a porta não é publicada para fora) |
| `KAFKA` | `localhost:9092` | Broker (no Docker de produção: `kafka:19092`) |
| `KAFKA_USER` · `KAFKA_PASSWORD` | obrigatórias | Login SASL/PLAIN do broker (sem elas o serviço recusa iniciar) |

Servidor HTTP: **Waitress** (servidor de produção; o `app.run` do Flask é só de desenvolvimento).

## Regra de negócio
O atraso e o limite dependem da **forma de pagamento** do pedido (campo `formaPagamento` do evento `pedidos-criados`;
a mesma tabela está em `specs/api.md`). O atraso base ("falar com o banco") é de **3 segundos**.

| `formaPagamento` | Espera | Decisão |
|---|---|---|
| `PIX` | 1/3 do atraso (~1 s) | sempre `APROVADO` |
| `CREDITO` · `DEBITO` | 3 s | até **R$ 100,00** `APROVADO`; acima, `RECUSADO` com motivo `"limite do cartão de teste"` |
| `DINHEIRO` | nenhuma (paga no balcão) | sempre `APROVADO` |

Evento sem `formaPagamento` (versão 1) ou com forma desconhecida: vale a regra do **crédito**.
Evento quebrado (JSON inválido, sem `pedidoId` ou `valor`, `valor` negativo, `NaN` ou não numérico) é **ignorado**:
uma linha de log ("evento inválido ignorado") e o serviço segue com o próximo, sem derrubar o consumidor.

## Ato 2 · por HTTP (síncrono)
`POST http://localhost:5001/pagamentos`
```json
{ "pedidoId": 12, "valor": 17.00 }
```
Resposta `200` depois de ~3 s:
```json
{ "pedidoId": 12, "status": "APROVADO", "motivo": null, "instancia": "caixa-1" }
```
Só existe na branch `ato-2`, onde ainda não havia forma de pagamento: vale só a regra de 3 s e R$ 100,00.
A partir do Ato 3 este endpoint saiu: ninguém liga para o Pagamentos.

## Ato 3 em diante · por Kafka (assíncrono)
Contrato dos eventos em `specs/eventos.md`.
- Lê `pedidos-criados` no grupo `pagamentos` (consumidor em thread própria, `auto.offset.reset = earliest`).
- Para cada pedido aplica a regra acima e publica em `pagamentos-resultados`, com a mesma chave (`pedidoId`)
  e o campo `instancia` preenchido com o nome desta instância.
- Uma linha de log por decisão, com partição, offset, pedido e resultado.
- Se o Pagamentos estiver parado, os pedidos esperam no tópico (lag do grupo `pagamentos`) e são decididos quando ele voltar.
- Receber o mesmo pedido mais de uma vez (ex.: `POST /pedidos/reenfileirar`) é seguro: a API aceita só o primeiro resultado.

### Mais de uma instância (Ato 5)
Todas no grupo `pagamentos` dividem as **partições** de `pedidos-criados`. Segunda instância:
`INSTANCIA=caixa-2 PORTA=5002 python app.py`. Se uma parar, o grupo se rebalanceia (em até ~10 s) e a outra assume as partições dela.

## Sempre
- `GET /saude` → `200 {"ok": true, "instancia": "caixa-1"}` se a thread que lê o Kafka está viva; se ela morreu, `503` com `"ok": false`
  (o caixa não está pagando nada: não diga que está tudo bem). É o que o Docker usa para a verificação de saúde.
- `GET /pagamentos` (Ato 3 em diante) → `200` com as **últimas 200 decisões** tomadas por **esta instância** desde que subiu.
  Cada item tem o formato do evento de `pagamentos-resultados`. Fica só na memória: reiniciar zera a lista.

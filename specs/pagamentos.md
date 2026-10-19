# Contrato do serviço de Pagamentos (simulado)

Nome da instância: variável de ambiente `INSTANCIA` (padrão `caixa-1`). Porta: `PORTA` (padrão `5001`).

Regra de negócio (igual em todos os atos):
- Demora **3 segundos** para "falar com o banco" (`time.sleep(3)`).
- `valor` até **R$ 100,00** → `APROVADO`.
- `valor` acima de R$ 100,00 → `RECUSADO`, motivo `"limite do cartão de teste"`.

## Ato 2 · por HTTP (síncrono)
`POST http://localhost:5001/pagamentos`
```json
{ "pedidoId": 12, "valor": 17.00 }
```
Resposta `200` depois de ~3 s:
```json
{ "pedidoId": 12, "status": "APROVADO", "motivo": null, "instancia": "caixa-1" }
```
Só existe na branch `ato-2`. A partir do Ato 3 este endpoint saiu: ninguém liga para o Pagamentos.

## Ato 3 em diante · por Kafka (assíncrono)
Contrato dos eventos em `specs/eventos.md`.
- Lê `pedidos-criados` no grupo `pagamentos` (consumidor em thread própria, `auto.offset.reset = earliest`).
- Para cada pedido aplica a regra acima e publica em `pagamentos-resultados`, com a mesma chave (`pedidoId`)
  e o campo `instancia` preenchido com o nome desta instância.
- Uma linha de log por decisão, com partição, offset, pedido e resultado.
- Se o Pagamentos estiver parado, os pedidos esperam no tópico (lag do grupo `pagamentos`) e são decididos quando ele voltar.

### Mais de uma instância (Ato 5)
Todas no grupo `pagamentos` dividem as **partições** de `pedidos-criados`. Segunda instância:
`INSTANCIA=caixa-2 PORTA=5002 python app.py`. Se uma parar, o grupo se rebalanceia (em até ~10 s) e a outra assume as partições dela.

## Sempre
- `GET /saude` → `200 {"ok": true, "instancia": "caixa-1"}`.
- `GET /pagamentos` (Ato 3 em diante) → `200` com a lista das decisões tomadas por **esta instância** desde que subiu.
  Cada item tem o formato do evento de `pagamentos-resultados`. Fica só na memória: reiniciar zera a lista.

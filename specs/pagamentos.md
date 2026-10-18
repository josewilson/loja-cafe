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

## Sempre
`GET /saude` → `200 {"ok": true, "instancia": "caixa-1"}`.

# Contrato do serviço de Pagamentos (simulado)

Configuração por variável de ambiente (o serviço também lê `infra/.env` sozinho, se o arquivo existir):

| Variável | Padrão | Para quê |
|---|---|---|
| `INSTANCIA` | `caixa-1` | Nome desta instância (vai no campo `instancia` dos resultados) |
| `PORTA` | `5001` | Porta HTTP |
| `HOST` | `127.0.0.1` | Interface de rede (no Docker é `0.0.0.0`, e a porta não é publicada para fora) |
| `KAFKA` | `localhost:9092` | Broker (no Docker de produção: `kafka:19092`) |
| `KAFKA_USER` · `KAFKA_PASSWORD` | obrigatórias | Login SASL/PLAIN do broker (sem elas o serviço recusa iniciar) |

| `PIX_PROVEDOR` | `automatico` | Como o Pix é cobrado: `automatico`, `simulado` ou `mercadopago` (seção "Pix" abaixo) |
| `MP_ACCESS_TOKEN` · `MP_WEBHOOK_SECRET` | — | Só com `mercadopago`: token da API e chave secreta do webhook (as duas obrigatórias) |
| `PUBLIC_URL` | — | Endereço público do sistema; vira o `notification_url` das cobranças. Sem ele só vale a conferência periódica |
| `PIX_EXPIRA_MINUTOS` · `PIX_CONSULTA_SEGUNDOS` | `10` · `30` | Validade da cobrança · intervalo da conferência das cobranças em aberto |

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

## Pix (Fase 5)
A linha `PIX` da tabela acima vale com `PIX_PROVEDOR=automatico` (o padrão: nada muda). Com `simulado` ou `mercadopago`, o Pix passa a ser uma **cobrança**:

1. Chega `pedidos-criados` com `formaPagamento = PIX`. O Pagamentos cria a cobrança no provedor e publica em `pagamentos-resultados` o status
   `AGUARDANDO` com o campo `pix` (copia e cola, QR e validade; veja `specs/eventos.md`). O pedido continua `AGUARDANDO_PAGAMENTO`.
2. Quando o cliente paga, o Pagamentos publica `APROVADO`. Se a cobrança vence (`PIX_EXPIRA_MINUTOS`), publica `RECUSADO` com motivo `pix expirado`.
   Se o provedor está fora do ar ao criar a cobrança: `RECUSADO` com motivo `pix indisponível no momento` (o balcão cobra de outro jeito).
3. Como o Pagamentos descobre o pagamento (`mercadopago`): pelo **webhook** `POST /webhooks/mercadopago` (público, o Caddy encaminha só este caminho) e,
   como rede de segurança, perguntando ao provedor a cada `PIX_CONSULTA_SEGUNDOS` por cada cobrança em aberto. O webhook só vale com `x-signature`
   correta (HMAC-SHA256 com `MP_WEBHOOK_SECRET`) e **nunca** aprova pelo que o aviso diz: o Pagamentos consulta o pagamento no Mercado Pago e usa a resposta.
   Assinatura errada ou ausente: `401`. Mercado Pago fora do ar na consulta: `502` (ele reenvia).
4. A cobrança usa `external_reference = pedidoId` e a chave de idempotência `loja-cafe-pedido-<id>`: repetir o evento (`/pedidos/reenfileirar`) devolve
   a **mesma** cobrança, não cria outra.

`simulado` não fala com ninguém: `POST /pix/simular/<pedidoId>` (no Pagamentos, porta 5001; não passa pelo Caddy) faz de conta que o cliente pagou.
Dá `404` fora do modo `simulado` e quando o pedido não tem Pix em aberto **nesta instância**.

Limites: as cobranças em aberto vivem na memória da instância (reiniciar o Pagamentos as esquece até `POST /pedidos/reenfileirar`; o webhook, por outro lado,
funciona em qualquer instância). Só o Pix é real; crédito, débito e dinheiro continuam simulados.

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

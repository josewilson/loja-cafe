# ☕ Loja Café

Projeto de aula (Cap 03, Kafka): uma loja de café pequena, de ponta a ponta, construída em atos.
O cliente faz o pedido no navegador, a API grava no Postgres e avisa o Kafka, o Pagamentos decide
(aprova até R$ 100,00) e o status muda sozinho na tela.

```
front (React) ──HTTP──▶ pedidos-api (Spring) ──▶ Postgres
                              │  ▲
              pedidos-criados │  │ pagamentos-resultados
                              ▼  │
                     Kafka ◀──▶ pagamentos-py (Flask, 1 ou mais caixas)
                       └──────▶ analytics-py (painel de vendas)
```

| Pasta | O que é | Porta |
|---|---|---|
| `front/` | React + Vite | 5173 |
| `pedidos-api/` | Spring Boot 4 + Java 21 + Postgres | 8081 |
| `pagamentos-py/` | Python + Flask + Kafka | 5001 (5002 para a 2ª instância) |
| `analytics-py/` | Script Python | — |
| `infra/` | Postgres (5433), Kafka (9092) e kafka-ui (8090) | — |
| `specs/` | Contratos (API, eventos, pagamentos) e a coleção do Insomnia | — |

**Como rodar:** veja [COMO-RODAR.md](COMO-RODAR.md). Em resumo: `docker compose up -d --wait` em `infra/`,
depois a API, o Pagamentos e o front, cada um no seu terminal.

**Contratos:** tudo o que a API aceita e devolve está em [`specs/`](specs). Regras do projeto em [AGENTS.md](AGENTS.md).

**Testes:** `./mvnw test` (API, sem Docker), `python -m unittest -v` (Pagamentos) e `npm run lint` (front).

Projeto didático: não há login, estoque, frete nem pagamento real.

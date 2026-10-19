# Loja Café — instruções para agentes (Claude Code, Codex, Antigravity)

Projeto de aula (Cap 03, Kafka). Um sistema pequeno, de ponta a ponta, construído em atos.
Simplicidade vale mais que "boas práticas de produção": código curto, legível, comentado em português.

## Peças e portas
| Pasta | O que é | Porta |
|---|---|---|
| `front/` | React + Vite (JavaScript, sem TypeScript) | 5173 |
| `pedidos-api/` | Spring Boot 4 + Java 21 + Postgres | 8081 |
| `pagamentos-py/` | Python 3 + Flask | 5001 (5002 para a 2ª instância) |
| `analytics-py/` | Script Python, sem servidor web | — |
| `infra/compose.yml` | Postgres (5433) + Kafka (9092) + painel kafka-ui (8090) | 5433 / 9092 / 8090 |

## Regras
- Os contratos estão em `specs/`. **Não invente** endpoint, campo, tópico ou status que não esteja lá.
  Se precisar mudar um contrato, pare e me pergunte.
- Uma pasta por peça. Não crie monorepo com build compartilhado.
- Python sempre em `.venv` dentro da pasta da peça. Dependências em `requirements.txt`.
- Spring: pacote `br.com.lojacafe.pedidos`, sem camadas extras (controller, entidade, repositório e
  no máximo uma classe por integração). Nada de arquitetura hexagonal neste projeto.
- Senhas e chaves só em `infra/.env` (ignorado pelo Git) ou variáveis de ambiente; nunca no código. O modelo é `infra/.env.example`.
- Não rode `docker compose down -v` (apaga os eventos do Kafka e os pedidos do Postgres). Para parar: `docker compose stop`.
- Ao terminar cada tarefa: diga como eu rodo, o que devo ver e qual comando prova que funciona.

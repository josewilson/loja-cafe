# Loja Café — instruções para agentes (Claude Code, Codex, Antigravity)

Projeto de aula (Cap 03, Kafka). Um sistema pequeno, de ponta a ponta, construído em atos.
Simplicidade vale mais que "boas práticas de produção": código curto, legível, comentado em português.
(Depois dos Atos 1 a 5 o projeto seguiu um plano em fases rumo à produção: o estado está no `README.md`. As regras abaixo continuam valendo.)

## Peças e portas
| Pasta | O que é | Porta |
|---|---|---|
| `front/` | React + Vite (JavaScript, sem TypeScript) | 5173 |
| `pedidos-api/` | Spring Boot 4 + Java 21 + Postgres | 8081 |
| `pagamentos-py/` | Python 3 + Flask | 5001 (5002 para a 2ª instância) |
| `analytics-py/` | Script Python, sem servidor web | — |
| `infra/compose.yml` | Postgres (5433) + Kafka (9092) + painel kafka-ui (8090) | 5433 / 9092 / 8090 |
| `deploy/` | Produção: `compose.prod.yml` + Caddy (HTTPS). Só 80/443 expostas; a API fica em `/api` | 80 / 443 |
| `deploy/` (scripts) | `atualizar.sh` (atualiza/volta de versão), `restaurar.sh`, `status.sh`; manual em `deploy/OPERACAO.md` | — |
| `deploy/backup/` | Backup criptografado do Postgres, restauração e retenção (scripts de shell, imagem própria) | — |
| `scripts/` | `e2e.sh` (ponta a ponta), `testar-backup.sh`, `conferir-specs.js`, `criar-usuario.sh`, `link-senha.sh` | — |
| `.github/workflows/` | CI: testes, ponta a ponta, pilha de produção, backup e conferência das specs | — |

## Regras
- Os contratos estão em `specs/`. **Não invente** endpoint, campo, tópico ou status que não esteja lá.
  Se precisar mudar um contrato, pare e me pergunte.
- Uma pasta por peça. Não crie monorepo com build compartilhado.
- Python sempre em `.venv` dentro da pasta da peça. Dependências em `requirements.txt`.
- Spring: pacote `br.com.lojacafe.pedidos`, com subpacotes `controller`, `model` (entidades e enums), `repository` e
  `service` (uma classe por integração: Kafka, NFC-e). Nada de arquitetura hexagonal neste projeto.
- Senhas e chaves só em `infra/.env` (desenvolvimento) ou `deploy/.env` (produção), ambos ignorados pelo Git, ou variáveis de ambiente; nunca no código. Os modelos são `infra/.env.example` e `deploy/.env.example`.
- Não rode `docker compose down -v` (apaga os eventos do Kafka e os pedidos do Postgres). Para parar: `docker compose stop`.
- Ao terminar cada tarefa: diga como eu rodo, o que devo ver e qual comando prova que funciona.

## Antes de dar uma tarefa por pronta
- `node scripts/conferir-specs.js`: o código e as `specs/` dizem a mesma coisa (rotas, eventos, tópicos, papéis, status). Falha se divergirem.
- `cd pedidos-api && ./mvnw test` · `cd pagamentos-py && python -m unittest` · `cd front && npm run lint`.
- Com o sistema no ar: `bash scripts/e2e.sh` (fluxo completo, 14 passos). O CI roda tudo isso a cada push.

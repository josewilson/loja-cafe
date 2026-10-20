# Produção (uma máquina com Docker)

Tudo roda num `docker compose`: **Caddy** (HTTPS + tela), **pedidos-api**, **pagamentos**, **Postgres** e **Kafka**.
Só as portas 80 e 443 aparecem para fora; o resto fica na rede interna do Docker.

```
Internet ─ 443 ─► Caddy ── /      → tela (arquivos estáticos)
                       └─ /api/* → pedidos-api ─► Postgres
                                        └───────► Kafka ◄── pagamentos
```

> **Antes de vender de verdade:** o pagamento ainda é **simulado** (Fase 5 traz o Pix real) e não há nota fiscal (Fase 6).
> Esta pilha serve para uso interno e piloto com a equipe.

## O que a máquina precisa
Linux (ou qualquer sistema) com **Docker e Docker Compose**, **4 GB de RAM**, 2 CPUs e 40 GB de disco.
O Kafka sozinho pede cerca de 1 GB: máquina de 1 GB não serve. Em máquinas ARM (ex.: Oracle Ampere) as imagens funcionam sem mudança.

## Primeira subida

```bash
git clone https://github.com/josewilson/loja-cafe.git && cd loja-cafe
cp deploy/.env.example deploy/.env
nano deploy/.env          # TROQUE todas as senhas (veja abaixo) e escolha o SITE_ADDRESS
docker compose -f deploy/compose.prod.yml up -d --build
docker compose -f deploy/compose.prod.yml ps      # todos "healthy"
```
A primeira construção baixa o Maven, o npm e as imagens: leva alguns minutos. O Flyway cria o banco sozinho e a API cria o
**ADMIN** com `ADMIN_EMAIL` e `ADMIN_PASSWORD`. Entre com ele e crie as contas da equipe (`POST /usuarios`, ou o script `scripts/criar-usuario.sh`).

**Senhas fortes:** `openssl rand -base64 24 | tr -d '/+='`. O `deploy/.env` **nunca** vai para o Git (já está no `.gitignore`).

## O endereço (`SITE_ADDRESS`)
| Valor | O que acontece |
|---|---|
| `cafe.exemplo.com.br` | **HTTPS de verdade.** O Caddy pede e renova o certificado (Let's Encrypt) sozinho. O domínio precisa apontar para o IP da máquina e as portas 80 e 443 estarem abertas |
| `localhost` | HTTPS de teste, com certificado do próprio Caddy (o navegador avisa: é esperado) |
| `:80` | HTTP puro, **só para rede local confiável**. Sem HTTPS, as senhas viajam sem proteção |

## Verificar se está tudo bem
```bash
curl -s https://SEU-ENDERECO/api/saude        # {"status":"ok","banco":"ok","kafka":"ok"}
ENV_FILE=deploy/.env bash scripts/e2e.sh https://SEU-ENDERECO/api      # o teste de ponta a ponta de 14 passos
```
(Com `localhost`, use `CURL_OPTS=-k` no segundo comando, para aceitar o certificado de teste.) O `e2e.sh` cria lojas e produtos de
teste no banco: use-o num ambiente de teste, não no sistema em uso.

## Dia a dia
```bash
docker compose -f deploy/compose.prod.yml logs -f pedidos-api     # logs (ficam limitados a 3 × 10 MB por serviço)
docker compose -f deploy/compose.prod.yml restart pedidos-api     # reiniciar uma parte
docker compose -f deploy/compose.prod.yml stop                    # parar tudo SEM apagar dados
git pull && docker compose -f deploy/compose.prod.yml up -d --build   # atualizar para a versão nova
```
**Nunca use `docker compose down -v`**: o `-v` apaga os volumes, ou seja, o banco de pedidos e os certificados.

## O que protege esta instalação
- Só 80/443 expostas; Postgres, Kafka, API e Pagamentos sem porta publicada.
- Postgres e Kafka com senha (o Kafka usa SASL/PLAIN, seguro porque só trafega na rede interna do Docker).
- Contêineres da API e do Pagamentos rodam sem root; reiniciam sozinhos; têm limite de memória e verificação de saúde.
- Cabeçalhos de segurança no Caddy (HSTS, CSP na tela, sem `Server`); a API mantém a CSP restritiva dela.
- Limite de tentativas de login e senha com BCrypt (veja o `README.md` da raiz).

## Limites conhecidos
- **Kafka guarda mensagens por 7 dias** (para o disco não encher). O Postgres é a fonte da verdade dos pedidos.
- **O Kafka não entra no backup.** Depois de restaurar um backup, pedidos que estavam "aguardando pagamento" podem nunca ser resolvidos.
- **Uma única instância** de cada serviço: sem alta disponibilidade. Se a máquina cair, o sistema para até ela voltar.
- O limite de tentativas de login vive na memória da API (um servidor só). Com várias APIs, precisaria de contador compartilhado.

# Produção (uma máquina com Docker)

Tudo roda num `docker compose`: **Caddy** (HTTPS + tela), **pedidos-api**, **pagamentos**, **Postgres** e **Kafka**.
Só as portas 80 e 443 aparecem para fora; o resto fica na rede interna do Docker.

```
Internet ─ 443 ─► Caddy ── /      → tela (arquivos estáticos)
                       └─ /api/* → pedidos-api ─► Postgres
                                        └───────► Kafka ◄── pagamentos
```

> **Antes de vender de verdade:** só o **Pix** pode ser real (`PIX_PROVEDOR=mercadopago`, seção "Pix" abaixo); crédito, débito e dinheiro continuam **simulados**,
> e a nota fiscal só existe em modo de teste (`NFCE_PROVEDOR=simulado`; o real, `focus`, exige contrato, certificado A1 e o contador; veja `deploy/.env.example`). Sem isso há só o recibo sem valor fiscal (Fase 6). Com o padrão `PIX_PROVEDOR=automatico`, o Pix também é simulado: aprova na hora, sem cobrança.
> Esta pilha serve para uso interno e piloto com a equipe.

## O que a máquina precisa
Linux (ou qualquer sistema) com **Docker e Docker Compose**, **4 GB de RAM**, 2 CPUs e 40 GB de disco.
O Kafka sozinho pede cerca de 1 GB: máquina de 1 GB não serve. Em máquinas ARM (ex.: Oracle Ampere) as imagens funcionam sem mudança.

## Primeira subida

```bash
git clone https://github.com/josewilson/loja-cafe.git && cd loja-cafe
cp deploy/.env.example deploy/.env
bash deploy/gerar-chave-backup.sh ~/chave-backup-PRIVADA.txt   # par de chaves do backup (veja "Backup e restauração")
nano deploy/.env          # TROQUE todas as senhas, cole a chave PÚBLICA em BACKUP_AGE_RECIPIENT e escolha o SITE_ADDRESS
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

## Cartão
`CARTAO_MODO` em `deploy/.env`: `simulado` (padrão, aprova sozinho até R$ 100,00; só para teste) ou `maquininha` (o atendente confirma na tela o que a maquininha respondeu, com o NSU opcional;
use esta em produção). Contrato em `specs/pagamentos.md`. Mudar o valor: edite o `.env` e `docker compose -f deploy/compose.prod.yml up -d pagamentos`.

## Pix
Três modos, em `PIX_PROVEDOR` (`deploy/.env`):
- `automatico` (padrão): Pix simulado, aprova na hora. **Não use para vender.**
- `simulado`: gera um QR de mentira; você "paga" com `docker compose -f deploy/compose.prod.yml exec pagamentos python -c "import urllib.request as u; print(u.urlopen(u.Request('http://127.0.0.1:5001/pix/simular/ID', method='POST')).read())"` (troque `ID` pelo número do pedido).
- `mercadopago`: Pix de verdade. Passos (a conta é sua; começar pelo **sandbox** não tem custo):
  1. Em *mercadopago.com.br/developers* crie uma aplicação e copie o **Access Token** (o de **teste** primeiro) para `MP_ACCESS_TOKEN`.
  2. Em *Webhooks* da aplicação, cadastre `https://SEU-DOMINIO/webhooks/mercadopago`, evento **Pagamentos**, e copie a **chave secreta** para `MP_WEBHOOK_SECRET`.
  3. Ponha `PUBLIC_URL=https://SEU-DOMINIO`, `PIX_PROVEDOR=mercadopago` e rode `docker compose -f deploy/compose.prod.yml up -d pagamentos`.
  4. Faça um pedido Pix de R$ 1 e pague. Só depois troque para o token de produção.

Sem domínio público o webhook não chega, mas a conferência a cada 30 s ainda confirma o pagamento (com até 30 s de atraso). Contrato em `specs/pagamentos.md`.

## Backup e restauração

O backup roda **sozinho todo dia às 03:30** (fuso de São Paulo) num contêiner próprio. Ele copia o banco com `pg_dump`, confere o arquivo,
**criptografa**, guarda no servidor, envia para **fora do servidor** e apaga os antigos (guarda tudo dos últimos 14 dias e os de domingo por 8 semanas;
o mais novo nunca é apagado).

### A chave: o ponto mais importante
O backup é criptografado com o **par de chaves** do `age`:
- a **chave pública** fica no servidor (`BACKUP_AGE_RECIPIENT` no `deploy/.env`) e **só serve para criptografar**: quem invadir o servidor não consegue ler os backups;
- a **chave privada** é a única que descriptografa. **Guarde-a fora do servidor, em dois lugares** (gerenciador de senhas, pendrive no cofre).
  **Se você a perder, os backups viram lixo, e quem a tiver lê todos eles.**

```bash
bash deploy/gerar-chave-backup.sh ~/chave-backup-PRIVADA.txt     # gera o par; mostra a PÚBLICA para colar no deploy/.env
```
Sem `BACKUP_AGE_RECIPIENT` o `docker compose up` **recusa subir**: deploy sem backup não existe.

### A cópia fora do servidor
Sem ela, se a máquina morrer, o backup morre junto (o painel `status.sh` e o log avisam). Configure um armazenamento S3 gratuito
(por exemplo Cloudflare R2 ou Backblaze B2: confirme os limites do plano) no `deploy/.env`: veja o bloco `RCLONE_*` do `.env.example`.
Os arquivos já saem criptografados, então o provedor não lê nada.

### Conferir que o backup existe e funciona
```bash
bash deploy/status.sh                 # contêineres, saúde, idade do último backup, disco, pedidos parados (sai com erro se algo estiver mal)
bash deploy/restaurar.sh --listar     # os backups, neste servidor e fora dele
```
**Teste a restauração de verdade, uma vez por mês** (um backup que nunca foi restaurado é uma esperança, não um backup):
```bash
bash deploy/restaurar.sh NOME-DO-BACKUP ~/chave-backup-PRIVADA.txt        # restaura num banco NOVO (loja_cafe_restaurado), sem tocar no banco em uso
docker compose -f deploy/compose.prod.yml exec postgres psql -U loja -d loja_cafe_restaurado -c "select count(*) from pedido"
```

### Se precisar mesmo restaurar (o banco se perdeu ou estragou)
1. `bash deploy/restaurar.sh --baixar NOME` (se o backup só existe fora do servidor).
2. `bash deploy/restaurar.sh NOME ~/chave-backup-PRIVADA.txt --substituir`: pede que você digite `RESTAURAR`, faz um **backup de segurança do banco atual**,
   para a API, apaga e recria o banco com o backup e religa tudo.
3. Confira `https://SEU-ENDERECO/api/saude` e entre no sistema.
4. **Pedidos que estavam "aguardando pagamento"** no momento do backup podem nunca ser resolvidos (o Kafka não entra no backup).
   Como ADMIN, reenvie-os ao Pagamentos: `POST /api/pedidos/reenfileirar?minutos=0`. É seguro repetir.

Se o backup for de uma versão **mais nova** do sistema do que a que está rodando, a API recusa subir (o Flyway detecta); atualize o código e suba de novo.

## Vigília: ser avisado quando algo parar

Duas verificações gratuitas, de fora do servidor, que avisam **por e-mail**:

1. **O site caiu?** No [UptimeRobot](https://uptimerobot.com) (plano gratuito) crie um monitor do tipo *Keyword*:
   endereço `https://SEU-ENDERECO/api/saude`, palavra `"status":"ok"`, intervalo de 5 minutos, e seu e-mail como contato de alerta.
   Ele avisa se a API, o banco ou o Kafka falharem, ou se a máquina inteira cair.
2. **O backup parou de rodar?** No [Healthchecks.io](https://healthchecks.io) (plano gratuito) crie um *check* com período de **1 dia** e tolerância de **2 horas**,
   copie o endereço de ping para `HEALTHCHECK_URL` no `deploy/.env` e reinicie o backup (`docker compose -f deploy/compose.prod.yml up -d backup`).
   O backup avisa quando começa, quando termina (com o tamanho) e quando **falha** (com o motivo). Se ele simplesmente **não rodar**, o Healthchecks
   avisa por falta de ping: é isso que pega o caso de a máquina estar desligada.

(Confirme no site de cada serviço o que o plano gratuito inclui hoje. Não consegui criar essas contas por você: elas são suas.)

O backup também avisa, no log e no ping, quando o **disco passa de 90%**. Os logs dos contêineres ficam limitados (3 arquivos de 10 MB por serviço).

## Dia a dia
```bash
docker compose -f deploy/compose.prod.yml logs -f pedidos-api     # logs (ficam limitados a 3 × 10 MB por serviço)
docker compose -f deploy/compose.prod.yml restart pedidos-api     # reiniciar uma parte
docker compose -f deploy/compose.prod.yml stop                    # parar tudo SEM apagar dados
bash deploy/atualizar.sh                                          # atualizar para a versão nova (backup antes; volta sozinho se falhar)
```
**Nunca use `docker compose down -v`**: o `-v` apaga os volumes, ou seja, o banco de pedidos e os certificados.

Problemas, restauração, atualização e rotina: **[`OPERACAO.md`](OPERACAO.md)** (manual de operação).

## O que protege esta instalação
- Só 80/443 expostas; Postgres, Kafka, API e Pagamentos sem porta publicada.
- Postgres e Kafka com senha (o Kafka usa SASL/PLAIN, seguro porque só trafega na rede interna do Docker).
- Contêineres da API, do Pagamentos e do backup rodam sem root; reiniciam sozinhos; têm limite de memória e verificação de saúde.
- Cabeçalhos de segurança no Caddy (HSTS, CSP na tela, sem `Server`); a API mantém a CSP restritiva dela.
- Limite de tentativas de login e senha com BCrypt (veja o `README.md` da raiz).
- Backup criptografado com chave pública: o servidor não consegue ler os próprios backups.

## Limites conhecidos
- **Kafka guarda mensagens por 7 dias** (para o disco não encher). O Postgres é a fonte da verdade dos pedidos.
- **O Kafka não entra no backup.** Depois de restaurar, pedidos "aguardando pagamento" podem nunca ser resolvidos: use `POST /api/pedidos/reenfileirar` (ADMIN).
- **Uma única instância** de cada serviço: sem alta disponibilidade. Se a máquina cair, o sistema para até ela voltar.
- O limite de tentativas de login vive na memória da API (um servidor só). Com várias APIs, precisaria de contador compartilhado.

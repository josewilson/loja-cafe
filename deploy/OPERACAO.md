# Manual de operação (produção)

Para quem cuida do servidor. Todos os comandos rodam **na raiz do repositório, na máquina do servidor**.
Instalação e conceitos: [`README.md`](README.md) (nesta pasta).

```bash
C="docker compose -f deploy/compose.prod.yml"     # atalho usado abaixo
```

> **Regra de ouro:** nunca `docker compose down -v` (o `-v` apaga o banco e os certificados). Para parar: `$C stop`.

## Primeiro passo em qualquer problema
```bash
bash deploy/status.sh        # contêineres, /saude, último backup, disco, pedidos parados. Sai com 1 se algo estiver errado
$C ps                        # quem está "healthy", "unhealthy" ou reiniciando
```

## Rotina
| Quando | O quê |
|---|---|
| Todo dia | O Healthchecks avisa se o backup falhar ou não rodar. Sem aviso = tudo certo |
| Toda semana | `bash deploy/status.sh` e olhar disco e último backup |
| Todo mês | **Teste de restauração** (seção abaixo). Backup que nunca foi restaurado não é backup |
| Ao mudar a equipe | Desativar a conta de quem saiu (`PUT /usuarios/{id}/ativo`, tela do ADMIN) |
| Ao atualizar | `bash deploy/atualizar.sh` (seção abaixo) |

## Atualizar para a versão nova (e voltar atrás)
```bash
bash deploy/atualizar.sh --simular          # mostra o que mudaria; não altera nada
bash deploy/atualizar.sh                    # atualiza a branch atual a partir do GitHub
bash deploy/atualizar.sh --versao v1.2.0    # vai para uma branch, tag ou commit (serve para VOLTAR a uma versão antiga)
```
O script: faz um **backup novo** → guarda as imagens atuais como `:anterior` → **constrói tudo antes** de tocar nos contêineres
(se não compilar, nada muda) → sobe → espera a API, a tela e o Pagamentos ficarem saudáveis (até 180 s, `ESPERA_SEGUNDOS`).
**Se não ficarem saudáveis, ele volta sozinho** para a versão anterior.

| Código de saída | Significa |
|---|---|
| `0` | Atualizou (ou já estava na versão pedida) |
| `1` | Não atualizou: abortou antes de mexer, ou falhou e **voltou sozinho**. Veja `deploy/ultima-falha.log` |
| `2` | **Situação grave:** falhou e a volta também falhou. Siga "O site está fora do ar" e, se preciso, restaure o backup |

Logs: `deploy/atualizacoes.log` (histórico), `deploy/ultima-construcao.log` e `deploy/ultima-falha.log`.
Recusas comuns: alterações locais no código (`git status`), outra atualização em andamento (apague `deploy/.atualizando.lock` só se
tiver certeza de que nenhuma roda), disco acima de 90%, pilha fora do ar.

**Limite importante:** voltar de versão **não desfaz migrações do banco** (Flyway só avança). Uma versão antiga costuma funcionar com o banco
novo, mas se uma migração mudou algo incompatível, a volta correta é restaurar o backup feito no início da atualização.

## O site está fora do ar
1. `$C ps`. Se estiver tudo parado (a máquina reiniciou e o Docker não subiu): `$C up -d`.
2. Só o **caddy** com problema: `$C logs --tail=50 caddy`. Causa comum é o certificado (abaixo).
3. `$C up -d` é seguro: recria só o que estiver diferente e **não apaga dados**.
4. Não resolveu: `bash deploy/atualizar.sh --versao <última versão boa> --forcar`.

## A API não sobe ou fica reiniciando
```bash
$C logs --tail=100 pedidos-api
```
| O que aparece | Causa e saída |
|---|---|
| `Connection ... refused` / `password authentication failed` (Postgres) | Postgres fora do ar, ou `POSTGRES_PASSWORD` do `.env` mudou depois do primeiro uso. A senha de um banco **já criado** não muda trocando o `.env`: volte ao valor antigo |
| Falha do Flyway (`Migration ... failed`, `checksum`) | Migração com erro ou alterada depois de aplicada. Volte à versão anterior (`atualizar.sh --versao`) e avise quem desenvolve |
| `KAFKA_USER`/`KAFKA_PASSWORD`, `SASL authentication failed` | Senhas do Kafka erradas no `.env`. Corrija e `$C up -d` |
| `OutOfMemoryError` / contêiner morto sem erro | Falta de memória na máquina (precisa de 4 GB). Veja `free -m` |
| `Nenhum ADMIN cadastrado` | Defina `ADMIN_EMAIL` e `ADMIN_PASSWORD` (mínimo 8) no `deploy/.env` e `$C up -d pedidos-api` |

A tela ficar aberta sem a API: o balcão mostra erro de conexão. Os pedidos já feitos estão no Postgres, a salvo.

## Pedidos parados em "aguardando pagamento"
Aparecem no `status.sh`. Causas: Pagamentos fora do ar, ou o Kafka foi restaurado/limpo e perdeu mensagens.
```bash
$C ps pagamentos && $C logs --tail=30 pagamentos          # está healthy? (se não: $C restart pagamentos)
TOKEN=...   # entre como ADMIN (POST /api/auth/login) e copie o token
curl -s -X POST -H "Authorization: Bearer $TOKEN" "https://SEU-ENDERECO/api/pedidos/reenfileirar?minutos=0"
```
Isso publica de novo o evento dos pedidos parados. É seguro repetir: a API aceita só o **primeiro** resultado de cada pedido.
`minutos=0` reenvia todos os que ainda esperam; sem o parâmetro, só os parados há mais tempo que o padrão.

## Pix não confirma
O cliente pagou e o pedido continua "aguardando":
1. `$C logs --tail=50 pagamentos`: procure `Mercado Pago:` (token errado/vencido, Mercado Pago fora do ar) e `webhook`.
2. `401` nos avisos do webhook = `MP_WEBHOOK_SECRET` errado. A conferência a cada 30 s ainda confirma, mas corrija o segredo.
3. Pagamentos reiniciou: as cobranças em aberto só vivem na memória. Rode o reenfileirar (seção "Pedidos parados"): ele devolve a **mesma** cobrança, não cria outra.
4. Pix vencido (10 min) vira `RECUSADO` sozinho; se o cliente pagou depois, devolva o dinheiro pelo painel do Mercado Pago e refaça o pedido.

## Disco cheio
```bash
df -h .  &&  docker system df
```
1. Backups locais antigos já são apagados pela retenção (14 dias + 8 domingos). Confira `$C exec backup ls -l /backups`.
2. Imagens velhas de construções: `docker image prune -f` (remove só imagens sem uso; **não** toca em volumes).
3. **Nunca** `docker volume prune` nem `system prune --volumes`: apagam dados.
4. Crescimento contínuo do `pedidos`/Kafka: o Kafka guarda 7 dias; o Postgres cresce com as vendas. Considere disco maior.

## Backup não rodou ou falhou
O Healthchecks avisou, ou o `status.sh` mostra "último backup" antigo.
```bash
$C logs --tail=50 backup
$C exec backup backup.sh          # roda um agora, na mão
```
- `age: ... recipient`: `BACKUP_AGE_RECIPIENT` errado (precisa ser a chave **pública**, `age1...`).
- `rclone`/cópia de fora: credenciais ou `RCLONE_DEST` errados. O backup **local** continua sendo feito; conserte a cópia de fora logo.
- Disco cheio: seção acima.

## Restaurar (banco perdido ou estragado)
A chave **privada** (`gerar-chave-backup.sh`) é indispensável e **não está no servidor**. Sem ela, os backups não abrem.
```bash
bash deploy/restaurar.sh --listar                 # escolha o arquivo .dump.age
bash deploy/restaurar.sh ARQUIVO.dump.age /caminho/da/chave-PRIVADA.txt            # restaura num banco NOVO (loja_cafe_restaurado), sem tocar no atual
bash deploy/restaurar.sh ARQUIVO.dump.age /caminho/da/chave-PRIVADA.txt --substituir   # troca o banco em uso (pede que você digite RESTAURAR)
```
Depois de substituir: reinicie a API (`$C restart pedidos-api`) e rode o reenfileirar da seção "Pedidos parados" (o Kafka não entra no backup).

**Teste mensal:** restaure sem `--substituir` e compare:
```bash
Q="select (select count(*) from pedido) || '/' || (select count(*) from usuario)"
$C exec -T postgres psql -U loja -d loja_cafe -Atc "$Q"
$C exec -T postgres psql -U loja -d loja_cafe_restaurado -Atc "$Q"     # os dois números devem ser iguais (ou quase: o original andou desde o backup)
```
Apague o banco de teste depois: `$C exec -T postgres psql -U loja -d postgres -c "drop database loja_cafe_restaurado"`.

## Certificado HTTPS não emitido
`$C logs caddy | grep -i -E "obtain|error|acme"`. Confira:
1. `SITE_ADDRESS` é um domínio real, sem `http://` na frente.
2. O DNS do domínio aponta para o IP **desta** máquina (`nslookup SEU-DOMINIO`).
3. As portas **80 e 443** estão abertas no firewall e no painel da hospedagem (a 80 é usada para provar que o domínio é seu).
4. Não repita tentativas sem parar: o Let's Encrypt limita falhas por hora. Corrija a causa e `$C restart caddy`.

## Trocar senhas e chaves
Edite `deploy/.env` e rode `$C up -d` (recria só o que depende do valor).
- **`POSTGRES_PASSWORD`**: a senha de um banco **já existente** vive dentro dele. Troque lá primeiro:
  `$C exec -T postgres psql -U loja -d loja_cafe -c "alter user loja password 'NOVA'"` e depois no `.env`.
- **`KAFKA_PASSWORD`**: troque no `.env` e `$C up -d` (kafka, API e Pagamentos juntos).
- **Senha de um usuário da tela**: o gerente (ou ADMIN) clica em "Nova senha" na aba Equipe e entrega o link à pessoa.
- **Ninguém consegue gerar o link (o único ADMIN esqueceu a senha):** no servidor, `COMPOSE=deploy/compose.prod.yml URL=https://SEU-SITE scripts/link-senha.sh admin@seu-email`
  imprime um link de 1 hora e uma vez. Abra-o e escolha a senha nova. (Não grava auditoria, porque não passa pela API.)
- **Alternativa para o ADMIN, se o script não servir:** o ADMIN é recriado a partir do `.env` quando não existe nenhum. Coloque a nova senha em `ADMIN_PASSWORD` e:
  ```bash
  $C exec -T postgres psql -U loja -d loja_cafe -c "delete from sessao where usuario_id in (select id from usuario where papel='ADMIN'); delete from usuario where papel='ADMIN';"
  $C restart pedidos-api       # recria o ADMIN com ADMIN_EMAIL / ADMIN_PASSWORD
  ```
  (Testado: o ADMIN não tem chave estrangeira em pedidos normalmente. Se o `delete` reclamar de chave estrangeira, o ADMIN já tem
  fechamentos de caixa/pedidos: use outra conta ADMIN, ou restaure.) Para trocar a senha de qualquer conta, desative-a e crie outra.
- **Chave do backup**: gere outro par (`gerar-chave-backup.sh`), troque `BACKUP_AGE_RECIPIENT` e **guarde as chaves privadas antigas**
  enquanto existirem backups feitos com elas.

## Logs
```bash
$C logs -f pedidos-api                  # acompanhar
$C logs --since 1h --tail=200 pagamentos
cat deploy/atualizacoes.log             # histórico de atualizações
```
Cada serviço guarda no máximo 3 arquivos de 10 MB. A trilha de quem fez o quê (cancelamentos, preços, contas) está na tabela `auditoria`
e na tela do ADMIN (`GET /auditoria`).

## Quando pedir ajuda
Reúna: `bash deploy/status.sh`, `$C logs --tail=100 <serviço>` do que falha e `cat deploy/ultima-falha.log` (se foi uma atualização).
**Nunca** cole o `deploy/.env` nem a chave privada do backup.

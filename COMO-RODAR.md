# Loja Café — como rodar cada peça

Um terminal por peça. Rode tudo a partir da pasta `loja-cafe/`.
Precisa de: Docker, Java 21 (`JAVA_HOME` apontando para o JDK 21), Node 20+ e Python 3.

## Infra: Postgres + Kafka + painel
```bash
cp infra/.env.example infra/.env   # só na primeira vez; abra o arquivo e TROQUE as senhas
cd infra
docker compose up -d --wait
```
Sem o `infra/.env` o compose não sobe e a API/Pagamentos/Analytics recusam iniciar (de propósito: não há senha padrão).
| O quê | Onde |
|---|---|
| Postgres (banco da pedidos-api) | `localhost:5433` · banco `loja_cafe` · usuário e senha em `infra/.env` |
| Kafka | `localhost:9092` |
| Painel do Kafka (kafka-ui) | http://localhost:8090 |

Parar sem apagar nada: `docker compose stop`.
**Nunca** use `docker compose down -v`: apaga os eventos do Kafka **e os pedidos do Postgres**.
A porta 5433 existe porque a 5432 costuma estar ocupada por outro Postgres; para mudar, `POSTGRES_PORT=5434 docker compose up -d`
(e `DB_URL=jdbc:postgresql://localhost:5434/loja_cafe` na API).

### Conectar no DBeaver
1. Nova conexão → **PostgreSQL**.
2. Host `localhost`, porta `5433`, banco `loja_cafe`, usuário e senha do `infra/.env` (`POSTGRES_USER` / `POSTGRES_PASSWORD`). Testar e salvar.
3. Para criar **outro banco seu**: botão direito em *Databases* → *Create New Database*. O usuário `loja` é administrador do servidor.
4. As tabelas da API (`loja`, `usuario`, `sessao`, `produto`, `estoque_loja`, `pedido`, `item_pedido`, `item_pedido_extras`, `auditoria`, `fechamento_caixa`, `insumo`, `estoque_insumo`, `ficha_produto`, `ficha_extra`, `consumo_pedido`, `flyway_schema_history`) aparecem em `loja_cafe` → `Schemas` → `public` depois que a API sobe.

### Testar no Insomnia
*Application → Import* e escolha `specs/loja-cafe.postman_collection.json` (a coleção tem as chamadas da API e do Pagamentos).
A variável `baseUrl` é `http://localhost:8081`.

## pedidos-api (Spring, porta 8081)
Precisa do Postgres e do Kafka no ar (`docker compose up -d --wait` acima).
```bash
cd pedidos-api
./mvnw spring-boot:run
```
Windows: `mvnw.cmd spring-boot:run`.

**Primeira partida:** o Flyway cria o esquema, o cardápio (4 produtos) e a loja "Matriz", e a API cria o **ADMIN** com `ADMIN_EMAIL` e `ADMIN_PASSWORD` do `infra/.env`.
Não existe cadastro público: crie as contas da equipe como ADMIN.
```bash
scripts/criar-usuario.sh bia@loja.com Bia ATENDENTE 1      # e-mail, nome, papel (ADMIN|GERENTE|ATENDENTE|COZINHA), id da loja; pede a senha
scripts/criar-usuario.sh ze@loja.com Zé GERENTE 1
```
O gerente cria atendentes e cozinha da própria loja pela API (`POST /usuarios`); o ADMIN cria lojas (`POST /lojas`) e define o estoque de cada uma. Veja `specs/api.md`.

**Começar do zero:** com a API parada, apague as tabelas no DBeaver (ou `docker compose exec postgres psql -U loja -d loja_cafe -c "drop schema public cascade; create schema public"`, dentro de `infra/`). As migrações recriam tudo na próxima partida.
**Mudou o banco?** Crie um arquivo novo com o próximo número livre (hoje `V9__descricao.sql`) em `pedidos-api/src/main/resources/db/migration`; nunca edite uma migração já aplicada.

As credenciais vêm de `infra/.env` (ou de variáveis de ambiente com o mesmo nome, que têm prioridade). Outro banco: `DB_URL`, `DB_USER`, `DB_PASSWORD`. Outra origem de CORS: `lojacafe.cors.origem`.

Testes (113; não precisam de Docker: usam H2 em memória): `./mvnw test`. A API também responde `GET /saude` (banco e Kafka) sem login.

## front (React, porta 5173)
```bash
cd front
npm install
npm run dev
```
Abra http://localhost:5173. Lint: `npm run lint`.

## pagamentos-py (Flask, porta 5001)
```bash
cd pagamentos-py
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python app.py
```
Windows: `py -m venv .venv` e `.venv\Scripts\activate`.
Segunda instância (Ato 5), em outro terminal com o `.venv` ativo:
```bash
INSTANCIA=caixa-2 PORTA=5002 python app.py
```
PowerShell: `$env:INSTANCIA="caixa-2"; $env:PORTA="5002"; python app.py`

Testes: `python -m unittest -v` (dentro de `pagamentos-py/`).

## analytics-py (Ato 4)
```bash
cd analytics-py
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python analytics.py
```
Outro grupo: `GRUPO=auditoria python analytics.py` (PowerShell: `$env:GRUPO="auditoria"; python analytics.py`).

Replay do grupo analytics (com o Analytics parado), dentro de `infra/`:
```bash
# O Kafka exige login: --command-config aponta para o arquivo de credenciais que o próprio contêiner mantém em /tmp/cliente.properties.
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --command-config /tmp/cliente.properties --group analytics --all-topics --reset-offsets --to-earliest --execute
```

As mensagens ficam 7 dias no Kafka: o replay só reconstrói o que ainda está dentro dessa janela (veja `specs/eventos.md`).

## Verificar que tudo está de acordo (antes de dar algo por pronto)
```bash
node scripts/conferir-specs.js     # o código e as specs/ dizem a mesma coisa
bash scripts/e2e.sh                # fluxo completo, com infra, API e Pagamentos no ar (14 passos)
bash scripts/testar-backup.sh      # backup e restauração num Postgres descartável (só precisa de Docker)
```

## Produção, backup e restauração
Tudo está em [`deploy/README.md`](deploy/README.md): subir com Docker e HTTPS, a chave do backup, a restauração, o `deploy/status.sh` e os alertas.

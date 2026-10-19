# Loja Café — como rodar cada peça

Um terminal por peça. Rode tudo a partir da pasta `loja-cafe/`.
Precisa de: Docker, Java 21 (`JAVA_HOME` apontando para o JDK 21), Node 20+ e Python 3.

## Infra: Postgres + Kafka + painel
```bash
cd infra
docker compose up -d --wait
```
| O quê | Onde |
|---|---|
| Postgres (banco da pedidos-api) | `localhost:5433` · banco `loja_cafe` · usuário `loja` · senha `loja` |
| Kafka | `localhost:9092` |
| Painel do Kafka (kafka-ui) | http://localhost:8090 |

Parar sem apagar nada: `docker compose stop`.
**Nunca** use `docker compose down -v`: apaga os eventos do Kafka **e os pedidos do Postgres**.
A porta 5433 existe porque a 5432 costuma estar ocupada por outro Postgres; para mudar, `POSTGRES_PORT=5434 docker compose up -d`
(e `DB_URL=jdbc:postgresql://localhost:5434/loja_cafe` na API).

### Conectar no DBeaver
1. Nova conexão → **PostgreSQL**.
2. Host `localhost`, porta `5433`, banco `loja_cafe`, usuário `loja`, senha `loja`. Testar e salvar.
3. Para criar **outro banco seu**: botão direito em *Databases* → *Create New Database*. O usuário `loja` é administrador do servidor.
4. As tabelas da API (`pedido`, `pedido_extras`, `produto`) aparecem em `loja_cafe` → `Schemas` → `public` depois que a API sobe.

### Testar no Insomnia
*Application → Import* e escolha `specs/loja-cafe.postman_collection.json` (a coleção tem as chamadas da API e do Pagamentos).
A variável `baseUrl` é `http://localhost:8081`.

## pedidos-api (Spring, porta 8081)
Precisa do Postgres e do Kafka no ar (`docker compose up -d --wait` acima).
```bash
cd pedidos-api
./mvnw spring-boot:run
```
Windows: `mvnw.cmd spring-boot:run`. Para começar do zero: com a API parada, apague as tabelas no DBeaver
(ou `docker compose exec postgres psql -U loja -d loja_cafe -c "drop table pedido_extras, pedido, produto"`).
O cardápio (4 produtos) é recriado sozinho na próxima partida.

Outro banco ou senha: variáveis `DB_URL`, `DB_USER`, `DB_PASSWORD`. Outra origem de CORS: `lojacafe.cors.origem`.

Testes (não precisam de Docker: usam H2 em memória): `./mvnw test`.

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
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --group analytics --all-topics --reset-offsets --to-earliest --execute
```

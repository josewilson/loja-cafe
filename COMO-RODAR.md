# Loja Café — como rodar cada peça

Um terminal por peça. Rode tudo a partir da pasta `loja-cafe/`.
Se uma pasta ainda não existe, é porque o ato dela ainda não chegou.

## Kafka + painel (a partir do Ato 3)
```bash
cd infra
docker compose up -d --wait
```
Painel: http://localhost:8090 · Parar sem apagar nada: `docker compose stop`
**Nunca** use `docker compose down -v`: apaga os eventos do Kafka.

## pedidos-api (Spring, porta 8081)
```bash
cd pedidos-api
./mvnw spring-boot:run
```
Windows: `mvnw.cmd spring-boot:run`. Os pedidos ficam em `pedidos-api/dados/` (H2 em arquivo).
Para começar do zero: pare a API e apague a pasta `dados/`.

## front (React, porta 5173)
```bash
cd front
npm install
npm run dev
```
Abra http://localhost:5173

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

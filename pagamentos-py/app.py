"""Pagamentos simulado da Loja Café · Ato 3: lê pedidos do Kafka e publica o resultado.

Não atende mais POST /pagamentos: ninguém liga para cá. O pedido chega pelo tópico
pedidos-criados e a decisão sai pelo tópico pagamentos-resultados (specs/eventos.md).
"""
import json
import os
import threading
import time
from datetime import datetime, timezone

from confluent_kafka import Consumer, Producer
from flask import Flask, jsonify

INSTANCIA = os.getenv("INSTANCIA", "caixa-1")
PORTA = int(os.getenv("PORTA", "5001"))
KAFKA = os.getenv("KAFKA", "localhost:9092")
# O Kafka do compose só escuta em IPv4; sem isto o log enche de "Connect to ipv6#[::1] failed".
IPV4 = {"broker.address.family": "v4"}
LIMITE = 100.00  # acima disso, o "cartão de teste" recusa

app = Flask(__name__)
processados = []  # o que ESTA instância decidiu desde que subiu (fica só na memória)
parar = threading.Event()


ATRASO = 3  # segundos "falando com o banco" (os testes zeram isto)
ouvinte = None  # a thread que lê o Kafka; /saude olha se ela ainda está viva


def decidir(valor):
    """Regra de negócio: devolve (status, motivo)."""
    time.sleep(ATRASO)  # finge que está falando com o banco
    if valor <= LIMITE:
        return "APROVADO", None
    return "RECUSADO", "limite do cartão de teste"


def resultado_do_evento(texto):
    """Lê um evento de pedidos-criados e devolve o resultado a publicar.

    Evento quebrado (JSON ruim, sem pedidoId/valor, valor negativo ou NaN) levanta
    ValueError/KeyError/TypeError: quem chama registra e segue, sem derrubar a thread.
    """
    pedido = json.loads(texto)
    pedido_id = pedido["pedidoId"]
    valor = float(pedido["valor"])
    if not 0 <= valor < float("inf"):  # também rejeita NaN
        raise ValueError(f"valor inválido: {pedido['valor']!r}")
    status, motivo = decidir(valor)
    return {
        "versao": 1,
        "pedidoId": pedido_id,
        "status": status,
        "motivo": motivo,
        "instancia": INSTANCIA,
        "processadoEm": datetime.now(timezone.utc).isoformat(),
    }


def ouvir_kafka():
    consumidor = Consumer({
        "bootstrap.servers": KAFKA,
        "group.id": "pagamentos",          # todas as instâncias no MESMO grupo dividem o trabalho
        "auto.offset.reset": "earliest",   # grupo novo começa do início do tópico
        "session.timeout.ms": 10000,       # instância sumiu? em 10 s o grupo redistribui as partições
        **IPV4,
    })
    # murmur2_random = o mesmo cálculo de partição do cliente Java: a mesma chave cai
    # na mesma partição, venha a mensagem do Python, do Spring ou do terminal do Kafka.
    produtor = Producer({"bootstrap.servers": KAFKA, "partitioner": "murmur2_random", **IPV4})
    consumidor.subscribe(["pedidos-criados"])
    print(f"[{INSTANCIA}] ouvindo pedidos-criados no grupo 'pagamentos'", flush=True)

    while not parar.is_set():
        msg = consumidor.poll(1.0)
        if msg is None:
            continue
        if msg.error():
            print(f"[{INSTANCIA}] erro do Kafka: {msg.error()}", flush=True)
            continue

        try:
            resultado = resultado_do_evento(msg.value())
        except (ValueError, KeyError, TypeError) as erro:
            # Evento quebrado não pode parar o caixa: registra, pula e segue com o próximo.
            print(f"[{INSTANCIA}] partição {msg.partition()} · offset {msg.offset()} · "
                  f"evento inválido ignorado ({erro!r})", flush=True)
            continue
        # Mesma chave (pedidoId) do evento de entrada.
        produtor.produce("pagamentos-resultados", key=str(resultado["pedidoId"]), value=json.dumps(resultado))
        produtor.flush()
        processados.append(resultado)
        print(f"[{INSTANCIA}] partição {msg.partition()} · offset {msg.offset()} · "
              f"pedido {resultado['pedidoId']} -> {resultado['status']}", flush=True)

    consumidor.close()  # sai do grupo na hora; as partições vão para outra instância


@app.get("/pagamentos")
def listar():
    return jsonify(processados)


@app.get("/saude")
def saude():
    # Se a thread do Kafka morreu, o caixa não está pagando nada: não diga que está tudo bem.
    vivo = ouvinte is not None and ouvinte.is_alive()
    return jsonify(ok=vivo, instancia=INSTANCIA), (200 if vivo else 503)


if __name__ == "__main__":
    ouvinte = threading.Thread(target=ouvir_kafka, daemon=True)
    ouvinte.start()
    try:
        # use_reloader=False: o recarregador do Flask subiria o consumidor DUAS vezes.
        app.run(port=PORTA, use_reloader=False)
    finally:
        parar.set()      # Ctrl+C: avisa o consumidor para fechar
        time.sleep(1.5)  # dá tempo de ele sair do grupo

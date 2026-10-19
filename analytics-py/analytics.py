"""Analytics da Loja Café · Ato 4: chega DEPOIS e mesmo assim lê o histórico inteiro.

Ninguém avisou este programa de nada. Ele só lê os dois tópicos do Kafka (specs/eventos.md)
com um grupo próprio, 'analytics', e monta um painel de vendas.
"""
import json
import os
from collections import Counter

from confluent_kafka import Consumer

GRUPO = os.getenv("GRUPO", "analytics")

consumidor = Consumer({
    "bootstrap.servers": os.getenv("KAFKA", "localhost:9092"),
    "group.id": GRUPO,
    "auto.offset.reset": "earliest",  # grupo que nunca leu começa do offset 0
    "broker.address.family": "v4",
})
consumidor.subscribe(["pedidos-criados", "pagamentos-resultados"])

# O painel vive na MEMÓRIA deste programa. O Kafka guarda só o marcador (offset) do grupo.
# Indexado por pedidoId: ler o mesmo evento duas vezes não conta o pedido duas vezes.
pedidos = {}


def mostrar_painel():
    pagos = [p for p in pedidos.values() if p.get("status") == "APROVADO" and "valor" in p]
    faturamento = sum(p["valor"] for p in pagos)
    vendidos = Counter()
    for p in pagos:
        for i in p["itens"]:
            vendidos[i["item"]] += i["quantidade"]
    campeao = "%s (%d)" % vendidos.most_common(1)[0] if vendidos else "-"
    print(f"📊 pedidos vistos: {len(pedidos)} · pagos: {len(pagos)} · "
          f"faturamento: R$ {faturamento:.2f} · mais vendido: {campeao}\n", flush=True)


print(f"Analytics lendo no grupo '{GRUPO}'. Ctrl+C para sair.", flush=True)
try:
    while True:
        mensagens = consumidor.consume(num_messages=100, timeout=1.0)
        for msg in mensagens:
            if msg.error():
                print(f"erro do Kafka: {msg.error()}", flush=True)
                continue
            evento = json.loads(msg.value())
            pedido = pedidos.setdefault(evento["pedidoId"], {})
            if msg.topic() == "pedidos-criados":
                # v2 traz "itens"; evento v1 (antigo) traz "item" e "quantidade" soltos.
                itens = evento.get("itens") or [{"item": evento["item"], "quantidade": evento["quantidade"]}]
                pedido.update(itens=itens, valor=float(evento["valor"]))
            else:
                # Igual à pedidos-api: vale o primeiro resultado; repetição não muda nada.
                pedido.setdefault("status", evento["status"])
            print(f"  {msg.topic():<22} partição {msg.partition()} · offset {msg.offset():>3} · "
                  f"pedido {evento['pedidoId']}", flush=True)
        if mensagens:
            mostrar_painel()
except KeyboardInterrupt:
    pass
finally:
    consumidor.close()

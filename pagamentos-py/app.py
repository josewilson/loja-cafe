"""Pagamentos simulado da Loja Café · Ato 2: a pedidos-api liga para cá por HTTP e espera.

Contrato em specs/pagamentos.md.
"""
import os
import time

from flask import Flask, jsonify, request

INSTANCIA = os.getenv("INSTANCIA", "caixa-1")
PORTA = int(os.getenv("PORTA", "5001"))
LIMITE = 100.00  # acima disso, o "cartão de teste" recusa

app = Flask(__name__)


@app.post("/pagamentos")
def pagar():
    pedido = request.get_json()
    time.sleep(3)  # finge que está falando com o banco
    if float(pedido["valor"]) <= LIMITE:
        status, motivo = "APROVADO", None
    else:
        status, motivo = "RECUSADO", "limite do cartão de teste"
    print(f"[{INSTANCIA}] pedido {pedido['pedidoId']} valor {pedido['valor']} -> {status}", flush=True)
    return jsonify(pedidoId=pedido["pedidoId"], status=status, motivo=motivo, instancia=INSTANCIA)


@app.get("/saude")
def saude():
    return jsonify(ok=True, instancia=INSTANCIA)


if __name__ == "__main__":
    app.run(port=PORTA)

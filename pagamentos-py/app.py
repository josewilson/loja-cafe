"""Pagamentos simulado da Loja Café · Ato 3: lê pedidos do Kafka e publica o resultado.

Não atende mais POST /pagamentos: ninguém liga para cá. O pedido chega pelo tópico
pedidos-criados e a decisão sai pelo tópico pagamentos-resultados (specs/eventos.md).
"""
import json
import os
import threading
import time
from collections import deque
from datetime import datetime, timedelta, timezone
from pathlib import Path

from confluent_kafka import Consumer, Producer
from dotenv import load_dotenv
from flask import Flask, jsonify, request
from waitress import serve

import pix

INSTANCIA = os.getenv("INSTANCIA", "caixa-1")
PORTA = int(os.getenv("PORTA", "5001"))
HOST = os.getenv("HOST", "127.0.0.1")
KAFKA = os.getenv("KAFKA", "localhost:9092")
# O Kafka do compose só escuta em IPv4; sem isto o log enche de "Connect to ipv6#[::1] failed".
IPV4 = {"broker.address.family": "v4"}

# Credenciais do Kafka: variáveis de ambiente ou infra/.env (modelo: infra/.env.example). Ambiente tem prioridade.
load_dotenv(Path(__file__).resolve().parent.parent / "infra" / ".env")


def credencial(nome):
    valor = os.getenv(nome)
    if not valor:
        raise SystemExit(f"Defina {nome} (variável de ambiente ou infra/.env; modelo em infra/.env.example)")
    return valor


def seguranca_kafka():
    """Login SASL/PLAIN do broker (specs/eventos.md não muda: é só quem pode entrar)."""
    return {
        "security.protocol": "SASL_PLAINTEXT",
        "sasl.mechanisms": "PLAIN",
        "sasl.username": credencial("KAFKA_USER"),
        "sasl.password": credencial("KAFKA_PASSWORD"),
    }
LIMITE = 100.00  # acima disso, o "cartão de teste" recusa

# Pix (specs/pagamentos.md): "automatico" = como nos Atos anteriores (aprova em ~1 s, sem cobrança);
# "simulado" = cobrança falsa que você paga com POST /pix/simular/<id>; "mercadopago" = Pix de verdade.
PIX_PROVEDOR = os.getenv("PIX_PROVEDOR") or "automatico"
PIX_EXPIRA_MINUTOS = int(os.getenv("PIX_EXPIRA_MINUTOS", "10"))
PIX_CONSULTA_SEGUNDOS = int(os.getenv("PIX_CONSULTA_SEGUNDOS", "30"))  # rede de segurança para webhook que não chegou
MP_WEBHOOK_SECRET = os.getenv("MP_WEBHOOK_SECRET", "")
if PIX_PROVEDOR == "mercadopago":
    PIX = pix.MercadoPago(os.getenv("MP_ACCESS_TOKEN"), os.getenv("PUBLIC_URL", ""))
    if not MP_WEBHOOK_SECRET:
        raise SystemExit("PIX_PROVEDOR=mercadopago exige MP_WEBHOOK_SECRET: sem ele o webhook não prova que veio do Mercado Pago")
elif PIX_PROVEDOR == "simulado":
    PIX = pix.Simulado()
elif PIX_PROVEDOR == "automatico":
    PIX = None
else:
    raise SystemExit(f"PIX_PROVEDOR inválido: {PIX_PROVEDOR!r} (automatico, simulado ou mercadopago)")

app = Flask(__name__)
processados = deque(maxlen=200)  # últimas decisões DESTA instância (só memória; não cresce sem limite)
parar = threading.Event()


ATRASO = 3  # segundos "falando com o banco" (os testes zeram isto)
ouvinte = None  # a thread que lê o Kafka; /saude olha se ela ainda está viva
produtor = None  # criado pela thread do Kafka; o webhook também publica por ele
pendentes = {}   # pedidoId -> {"cobranca": id, "expiraEm": datetime} (só memória: /pedidos/reenfileirar repõe depois de um reinício)
trava = threading.Lock()


# Regra de cada forma (specs/api.md): fração do ATRASO que ela espera e se tem limite de valor.
# Forma ausente (evento v1) ou desconhecida cai na regra do crédito.
REGRAS = {
    "PIX": {"espera": 1 / 3, "limite": False},
    "CREDITO": {"espera": 1, "limite": True},
    "DEBITO": {"espera": 1, "limite": True},
    "DINHEIRO": {"espera": 0, "limite": False},  # paga no balcão: aprova na hora
}


def decidir(valor, forma=None):
    """Regra de negócio: devolve (status, motivo)."""
    regra = REGRAS.get(forma, REGRAS["CREDITO"])
    time.sleep(ATRASO * regra["espera"])  # finge que está falando com o banco
    if regra["limite"] and valor > LIMITE:
        return "RECUSADO", "limite do cartão de teste"
    return "APROVADO", None


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
    if PIX is not None and pedido.get("formaPagamento") == "PIX":
        return cobrar_pix(pedido_id, valor)
    status, motivo = decidir(valor, pedido.get("formaPagamento"))
    return montar(pedido_id, status, motivo)


def montar(pedido_id, status, motivo, cobranca=None):
    """O evento de pagamentos-resultados (specs/eventos.md). 'cobranca' só existe no status AGUARDANDO."""
    dados_pix = None if cobranca is None else {**cobranca, "expiraEm": cobranca["expiraEm"].isoformat()}
    return {
        "versao": 1,
        "pedidoId": pedido_id,
        "status": status,
        "motivo": motivo,
        "instancia": INSTANCIA,
        "processadoEm": datetime.now(timezone.utc).isoformat(),
        "pix": dados_pix,
    }


def cobrar_pix(pedido_id, valor):
    """Cria a cobrança no provedor e devolve AGUARDANDO com o QR. Se o provedor falhar, recusa: o balcão cobra de outro jeito."""
    with trava:
        existente = pendentes.get(pedido_id)
    expira_em = existente["expiraEm"] if existente else pix.agora() + timedelta(minutes=PIX_EXPIRA_MINUTOS)
    try:
        cobranca = PIX.criar(pedido_id, valor, expira_em)  # repetido (reenfileirar): o provedor devolve a mesma cobrança
    except pix.PixErro as erro:
        print(f"[{INSTANCIA}] pedido {pedido_id}: {erro}", flush=True)
        return montar(pedido_id, "RECUSADO", "pix indisponível no momento")
    with trava:
        pendentes[pedido_id] = {"cobranca": cobranca["id"], "expiraEm": expira_em}
    return montar(pedido_id, "AGUARDANDO", None, cobranca)


def publicar(resultado):
    """Publica em pagamentos-resultados com a mesma chave do pedido (pedidoId) e guarda na lista desta instância."""
    produtor.produce("pagamentos-resultados", key=str(resultado["pedidoId"]), value=json.dumps(resultado))
    produtor.flush()
    processados.append(resultado)


def concluir_pix(pedido_id, status, motivo=None):
    """Pix pago ou vencido: tira da espera e publica a decisão final (a API aceita só a primeira)."""
    with trava:
        pendentes.pop(pedido_id, None)
    publicar(montar(pedido_id, status, motivo))
    print(f"[{INSTANCIA}] pedido {pedido_id} -> {status}", flush=True)


def conferir_pendentes():
    """Pergunta ao provedor por cada cobrança em aberto e resolve as pagas e as vencidas."""
    with trava:
        lista = list(pendentes.items())
    for pedido_id, p in lista:
        try:
            estado = PIX.consultar(p["cobranca"])[1]
        except pix.PixErro as erro:
            print(f"[{INSTANCIA}] pedido {pedido_id}: {erro}", flush=True)
            continue  # tenta de novo na próxima volta
        if estado == "pago":
            concluir_pix(pedido_id, "APROVADO")
        elif estado == "falhou" or pix.agora() >= p["expiraEm"]:
            concluir_pix(pedido_id, "RECUSADO", "pix expirado")


def ouvir_kafka():
    consumidor = Consumer({
        "bootstrap.servers": KAFKA,
        "group.id": "pagamentos",          # todas as instâncias no MESMO grupo dividem o trabalho
        "auto.offset.reset": "earliest",   # grupo novo começa do início do tópico
        "session.timeout.ms": 10000,       # instância sumiu? em 10 s o grupo redistribui as partições
        **IPV4,
        **seguranca_kafka(),
    })
    global produtor
    # murmur2_random = o mesmo cálculo de partição do cliente Java: a mesma chave cai
    # na mesma partição, venha a mensagem do Python, do Spring ou do terminal do Kafka.
    produtor = Producer({"bootstrap.servers": KAFKA, "partitioner": "murmur2_random", **IPV4, **seguranca_kafka()})
    consumidor.subscribe(["pedidos-criados"])
    print(f"[{INSTANCIA}] ouvindo pedidos-criados no grupo 'pagamentos'", flush=True)

    ultima_conferencia = time.monotonic()
    while not parar.is_set():
        if PIX is not None and time.monotonic() - ultima_conferencia >= PIX_CONSULTA_SEGUNDOS:
            ultima_conferencia = time.monotonic()
            conferir_pendentes()
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
        publicar(resultado)  # mesma chave (pedidoId) do evento de entrada
        print(f"[{INSTANCIA}] partição {msg.partition()} · offset {msg.offset()} · "
              f"pedido {resultado['pedidoId']} -> {resultado['status']}", flush=True)

    consumidor.close()  # sai do grupo na hora; as partições vão para outra instância


@app.get("/pagamentos")
def listar():
    return jsonify(list(processados))


@app.post("/webhooks/mercadopago")
def webhook_mercadopago():
    """O Mercado Pago avisa que um pagamento mudou. Não confiamos no aviso: conferimos a assinatura e perguntamos de novo a ele."""
    if PIX_PROVEDOR != "mercadopago":
        return "", 404
    corpo = request.get_json(silent=True) or {}
    data_id = request.args.get("data.id") or (corpo.get("data") or {}).get("id")
    if not data_id or not pix.assinatura_valida(MP_WEBHOOK_SECRET, request.headers.get("x-signature"),
                                                request.headers.get("x-request-id"), data_id):
        return "", 401
    try:
        pedido_id, estado = PIX.consultar(str(data_id))
    except pix.PixErro as erro:
        print(f"[{INSTANCIA}] webhook: {erro}", flush=True)
        return "", 502  # o Mercado Pago tenta de novo
    if estado == "pago":
        concluir_pix(pedido_id, "APROVADO")
    return "", 200


@app.post("/pix/simular/<int:pedido_id>")
def simular_pagamento(pedido_id):
    """Só no provedor simulado: faz de conta que o cliente pagou o Pix."""
    if PIX_PROVEDOR != "simulado":
        return "", 404
    with trava:
        existe = pedido_id in pendentes
    if not existe:
        return jsonify(erro="esse pedido não tem Pix em aberto nesta instância"), 404
    concluir_pix(pedido_id, "APROVADO")
    return jsonify(pedidoId=pedido_id, status="APROVADO")


@app.get("/saude")
def saude():
    # Se a thread do Kafka morreu, o caixa não está pagando nada: não diga que está tudo bem.
    vivo = ouvinte is not None and ouvinte.is_alive()
    return jsonify(ok=vivo, instancia=INSTANCIA), (200 if vivo else 503)


if __name__ == "__main__":
    ouvinte = threading.Thread(target=ouvir_kafka, daemon=True)
    ouvinte.start()
    try:
        # Waitress é um servidor de produção (o app.run do Flask é só de desenvolvimento) e não recarrega sozinho,
        # então o consumidor não sobe duas vezes. host 127.0.0.1: só esta máquina acessa; atrás de um proxy, mude HOST.
        serve(app, host=HOST, port=PORTA)
    finally:
        parar.set()      # Ctrl+C: avisa o consumidor para fechar
        time.sleep(1.5)  # dá tempo de ele sair do grupo

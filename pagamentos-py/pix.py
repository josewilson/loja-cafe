"""Provedores de Pix do Pagamentos (Fase 5). Cada um cria uma cobrança e diz se ela já foi paga.

  Simulado     : sem internet nem conta. Gera um código de mentira; quem "paga" é POST /pix/simular/<pedidoId>.
  MercadoPago  : API real do Mercado Pago (sandbox ou produção, conforme o token). Só biblioteca padrão (urllib).

Contrato comum:
  criar(pedido_id, valor, expira_em) -> {"id", "copiaECola", "qrCode", "expiraEm"}
  consultar(cobranca_id)             -> (pedido_id, estado)   estado: "pago" | "pendente" | "falhou"
  estornar(pedido_id)                -> devolve o Pix pago do pedido (levanta PixErro se não conseguir)
"""
import hashlib
import hmac
import itertools
import json
import urllib.request
from datetime import datetime, timezone

# PNG de 1x1 pixel: o provedor simulado não desenha QR de verdade (o copia e cola serve para o teste).
PNG_VAZIO = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="


class PixErro(Exception):
    """O provedor não respondeu como esperado (fora do ar, token errado, valor recusado...)."""


class Simulado:
    def __init__(self):
        self._contador = itertools.count(1)
        self._pedidos = {}  # cobranca_id -> pedido_id

    def criar(self, pedido_id, valor, expira_em):
        cobranca = f"sim-{next(self._contador)}"
        self._pedidos[cobranca] = pedido_id
        return {"id": cobranca, "copiaECola": f"00020126SIMULADO{pedido_id}5204000053039865406{valor:.2f}6304TEST",
                "qrCode": PNG_VAZIO, "expiraEm": expira_em}

    def consultar(self, cobranca_id):
        # Quem confirma o pagamento simulado é a rota /pix/simular; aqui nunca "chega" sozinho.
        return self._pedidos.get(cobranca_id), "pendente"


    def estornar(self, pedido_id):
        pass  # nada a devolver de verdade


class MercadoPago:
    API = "https://api.mercadopago.com"

    def __init__(self, token, url_publica="", email_pagador="cliente@loja-cafe.com.br"):
        if not token:
            raise SystemExit("PIX_PROVEDOR=mercadopago exige MP_ACCESS_TOKEN (deploy/.env ou infra/.env)")
        self.token = token
        self.notificacao = f"{url_publica.rstrip('/')}/webhooks/mercadopago" if url_publica else None
        self.email = email_pagador

    def _chamar(self, metodo, caminho, corpo=None, chave=None):
        pedido = urllib.request.Request(self.API + caminho, method=metodo,
                                        data=json.dumps(corpo).encode() if corpo is not None else None)
        pedido.add_header("Authorization", f"Bearer {self.token}")
        pedido.add_header("Content-Type", "application/json")
        if chave:
            pedido.add_header("X-Idempotency-Key", chave)  # repetir o mesmo pedido devolve a MESMA cobrança
        try:
            with urllib.request.urlopen(pedido, timeout=10) as resposta:
                return json.loads(resposta.read())
        except Exception as erro:  # rede, HTTP 4xx/5xx, JSON: tudo vira PixErro (sem vazar o token)
            raise PixErro(f"Mercado Pago: {type(erro).__name__}") from erro

    def criar(self, pedido_id, valor, expira_em):
        corpo = {
            "transaction_amount": round(float(valor), 2),
            "description": f"Loja Café - pedido {pedido_id}",
            "payment_method_id": "pix",
            "external_reference": str(pedido_id),
            "payer": {"email": self.email},
            "date_of_expiration": expira_em.astimezone(timezone.utc).isoformat(timespec="milliseconds"),
        }
        if self.notificacao:
            corpo["notification_url"] = self.notificacao
        pagamento = self._chamar("POST", "/v1/payments", corpo, chave=f"loja-cafe-pedido-{pedido_id}")
        try:
            dados = pagamento["point_of_interaction"]["transaction_data"]
            return {"id": str(pagamento["id"]), "copiaECola": dados["qr_code"], "qrCode": dados["qr_code_base64"],
                    "expiraEm": expira_em}
        except (KeyError, TypeError) as erro:
            raise PixErro("Mercado Pago: resposta sem o QR code") from erro

    def consultar(self, cobranca_id):
        pagamento = self._chamar("GET", f"/v1/payments/{cobranca_id}")
        estado = {"approved": "pago", "pending": "pendente", "in_process": "pendente", "authorized": "pendente"}
        try:
            return int(pagamento["external_reference"]), estado.get(pagamento["status"], "falhou")
        except (KeyError, TypeError, ValueError) as erro:
            raise PixErro("Mercado Pago: pagamento sem external_reference") from erro

    def estornar(self, pedido_id):
        # Acha o pagamento aprovado pela referência do pedido e pede o reembolso total (a chave de idempotência evita devolver duas vezes).
        achados = self._chamar("GET", f"/v1/payments/search?external_reference={pedido_id}&status=approved")
        try:
            pagamento = achados["results"][0]["id"]
        except (KeyError, IndexError, TypeError) as erro:
            raise PixErro("Mercado Pago: pagamento aprovado não encontrado") from erro
        self._chamar("POST", f"/v1/payments/{pagamento}/refunds", {}, chave=f"loja-cafe-estorno-{pedido_id}")


def assinatura_valida(segredo, cabecalho, request_id, data_id):
    """Confere o x-signature do webhook do Mercado Pago ("ts=...,v1=<hmac>"). Sem segredo configurado, nada é aceito."""
    if not segredo or not cabecalho:
        return False
    partes = dict(p.strip().split("=", 1) for p in cabecalho.split(",") if "=" in p)
    if "ts" not in partes or "v1" not in partes:
        return False
    manifesto = f"id:{str(data_id).lower()};request-id:{request_id or ''};ts:{partes['ts']};"
    esperado = hmac.new(segredo.encode(), manifesto.encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(esperado, partes["v1"])


def agora():
    return datetime.now(timezone.utc)

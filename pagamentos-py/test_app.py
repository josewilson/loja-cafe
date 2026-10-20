"""Testes do Pagamentos, sem Kafka e sem esperar 3 s. Rode: python -m unittest -v (dentro de pagamentos-py/)."""
import hashlib
import hmac
import json
import unittest
from datetime import timedelta

import app
import pix

app.ATRASO = 0  # nada de time.sleep(3) nos testes


class RegraDeNegocio(unittest.TestCase):
    def test_ate_100_aprova(self):
        self.assertEqual(app.decidir(17.0), ("APROVADO", None))
        self.assertEqual(app.decidir(100.0), ("APROVADO", None))

    def test_acima_de_100_recusa_com_motivo(self):
        self.assertEqual(app.decidir(100.01), ("RECUSADO", "limite do cartão de teste"))
        self.assertEqual(app.decidir(170.0), ("RECUSADO", "limite do cartão de teste"))


class FormasDePagamento(unittest.TestCase):
    def test_pix_e_dinheiro_nao_tem_limite(self):
        for forma in ("PIX", "DINHEIRO"):
            with self.subTest(forma=forma):
                self.assertEqual(app.decidir(5000.0, forma), ("APROVADO", None))

    def test_cartoes_recusam_acima_de_100(self):
        for forma in ("CREDITO", "DEBITO"):
            with self.subTest(forma=forma):
                self.assertEqual(app.decidir(100.0, forma), ("APROVADO", None))
                self.assertEqual(app.decidir(100.01, forma), ("RECUSADO", "limite do cartão de teste"))

    def test_forma_ausente_ou_desconhecida_vale_como_credito(self):
        self.assertEqual(app.decidir(170.0, None)[0], "RECUSADO")
        self.assertEqual(app.decidir(170.0, "FIADO")[0], "RECUSADO")

    def test_evento_v2_usa_a_forma_do_pedido(self):
        r = app.resultado_do_evento(json.dumps({"pedidoId": 7, "valor": 250.0, "formaPagamento": "PIX"}))
        self.assertEqual(r["status"], "APROVADO")
        r = app.resultado_do_evento(json.dumps({"pedidoId": 8, "valor": 250.0, "formaPagamento": "DEBITO"}))
        self.assertEqual(r["status"], "RECUSADO")


class EventoDePedido(unittest.TestCase):
    def test_evento_valido_vira_resultado(self):
        r = app.resultado_do_evento(json.dumps({"pedidoId": 12, "valor": 17.0}))
        self.assertEqual((r["pedidoId"], r["status"], r["motivo"]), (12, "APROVADO", None))
        self.assertEqual((r["versao"], r["instancia"]), (1, app.INSTANCIA))

    def test_evento_quebrado_levanta_erro_em_vez_de_derrubar_a_thread(self):
        quebrados = [
            b"isto nao e json",
            b"{}",
            json.dumps({"pedidoId": 1}),                    # sem valor
            json.dumps({"valor": 10}),                      # sem pedidoId
            json.dumps({"pedidoId": 1, "valor": "abc"}),    # valor não numérico
            json.dumps({"pedidoId": 1, "valor": None}),
            json.dumps({"pedidoId": 1, "valor": -5}),       # negativo
            '{"pedidoId": 1, "valor": NaN}',                # NaN
            b"[1, 2]",                                      # JSON, mas não é um objeto
        ]
        for texto in quebrados:
            with self.subTest(texto=texto):
                with self.assertRaises((ValueError, KeyError, TypeError)):
                    app.resultado_do_evento(texto)


class Saude(unittest.TestCase):
    def test_sem_a_thread_do_kafka_nao_diz_que_esta_ok(self):
        app.ouvinte = None
        resposta = app.app.test_client().get("/saude")
        self.assertEqual(resposta.status_code, 503)
        self.assertFalse(resposta.get_json()["ok"])


class ProdutorFalso:
    def __init__(self):
        self.enviados = []

    def produce(self, topico, key, value):
        self.enviados.append((topico, key, json.loads(value)))

    def flush(self):
        pass


class MPFalso(pix.MercadoPago):
    """Mercado Pago sem internet: _chamar devolve o que o teste mandar."""
    def __init__(self, respostas):
        super().__init__("token-de-teste", "https://loja.exemplo.com.br")
        self.respostas, self.chamadas = respostas, []

    def _chamar(self, metodo, caminho, corpo=None, chave=None):
        self.chamadas.append((metodo, caminho, corpo, chave))
        resposta = self.respostas[(metodo, caminho)]
        if isinstance(resposta, Exception):
            raise resposta
        return resposta


class PixBase(unittest.TestCase):
    def preparar(self, provedor, nome):
        antes = (app.PIX, app.PIX_PROVEDOR, app.produtor, app.MP_WEBHOOK_SECRET)
        app.PIX, app.PIX_PROVEDOR, app.produtor, app.MP_WEBHOOK_SECRET = provedor, nome, ProdutorFalso(), "segredo"
        app.pendentes.clear()
        self.addCleanup(lambda: (setattr(app, "PIX", antes[0]), setattr(app, "PIX_PROVEDOR", antes[1]),
                                 setattr(app, "produtor", antes[2]), setattr(app, "MP_WEBHOOK_SECRET", antes[3])))

    def evento_pix(self, pedido_id=5, valor=17.0):
        return json.dumps({"pedidoId": pedido_id, "valor": valor, "formaPagamento": "PIX"})


class PixSimulado(PixBase):
    def setUp(self):
        self.preparar(pix.Simulado(), "simulado")

    def test_pix_gera_cobranca_e_fica_aguardando(self):
        r = app.resultado_do_evento(self.evento_pix())
        self.assertEqual((r["status"], r["motivo"]), ("AGUARDANDO", None))
        self.assertTrue(r["pix"]["copiaECola"] and r["pix"]["qrCode"] and r["pix"]["expiraEm"])
        self.assertIn(5, app.pendentes)

    def test_outras_formas_nao_mudam(self):
        r = app.resultado_do_evento(json.dumps({"pedidoId": 6, "valor": 17.0, "formaPagamento": "DINHEIRO"}))
        self.assertEqual((r["status"], r["pix"]), ("APROVADO", None))

    def test_pagar_publica_aprovado_e_tira_da_espera(self):
        app.resultado_do_evento(self.evento_pix())
        resposta = app.app.test_client().post("/pix/simular/5")
        self.assertEqual(resposta.status_code, 200)
        topico, chave, evento = app.produtor.enviados[-1]
        self.assertEqual((topico, chave, evento["status"]), ("pagamentos-resultados", "5", "APROVADO"))
        self.assertNotIn(5, app.pendentes)
        self.assertEqual(app.app.test_client().post("/pix/simular/5").status_code, 404)  # já pago

    def test_vencido_vira_recusado(self):
        app.resultado_do_evento(self.evento_pix())
        app.pendentes[5]["expiraEm"] = pix.agora() - timedelta(seconds=1)
        app.conferir_pendentes()
        evento = app.produtor.enviados[-1][2]
        self.assertEqual((evento["status"], evento["motivo"]), ("RECUSADO", "pix expirado"))
        self.assertNotIn(5, app.pendentes)

    def test_nao_vencido_continua_esperando(self):
        app.resultado_do_evento(self.evento_pix())
        app.conferir_pendentes()
        self.assertEqual(app.produtor.enviados, [])
        self.assertIn(5, app.pendentes)

    def test_rota_de_simulacao_nao_existe_fora_do_simulado(self):
        app.PIX_PROVEDOR = "automatico"
        self.assertEqual(app.app.test_client().post("/pix/simular/5").status_code, 404)


class PixAutomatico(PixBase):
    def test_sem_provedor_o_pix_continua_aprovando_na_hora(self):
        self.preparar(None, "automatico")
        r = app.resultado_do_evento(self.evento_pix(valor=5000.0))
        self.assertEqual((r["status"], r["pix"]), ("APROVADO", None))


class PixMercadoPago(PixBase):
    PAGAMENTO = {"id": 987, "status": "pending", "external_reference": "5",
                 "point_of_interaction": {"transaction_data": {"qr_code": "00020126MP", "qr_code_base64": "QUJD"}}}

    def setUp(self):
        self.mp = MPFalso({("POST", "/v1/payments"): self.PAGAMENTO,
                           ("GET", "/v1/payments/987"): {**self.PAGAMENTO, "status": "approved"}})
        self.preparar(self.mp, "mercadopago")

    def test_cria_cobranca_com_valor_referencia_idempotencia_e_aviso(self):
        r = app.resultado_do_evento(self.evento_pix(valor=17.5))
        self.assertEqual((r["status"], r["pix"]["copiaECola"], r["pix"]["qrCode"]), ("AGUARDANDO", "00020126MP", "QUJD"))
        metodo, caminho, corpo, chave = self.mp.chamadas[0]
        self.assertEqual((corpo["transaction_amount"], corpo["external_reference"], corpo["payment_method_id"]), (17.5, "5", "pix"))
        self.assertEqual(corpo["notification_url"], "https://loja.exemplo.com.br/webhooks/mercadopago")
        self.assertEqual(chave, "loja-cafe-pedido-5")

    def test_provedor_fora_do_ar_recusa_com_motivo(self):
        self.mp.respostas[("POST", "/v1/payments")] = pix.PixErro("Mercado Pago: URLError")
        r = app.resultado_do_evento(self.evento_pix())
        self.assertEqual((r["status"], r["motivo"], r["pix"]), ("RECUSADO", "pix indisponível no momento", None))
        self.assertNotIn(5, app.pendentes)

    def test_conferencia_acha_o_pagamento_aprovado(self):
        app.resultado_do_evento(self.evento_pix())
        app.conferir_pendentes()
        self.assertEqual(app.produtor.enviados[-1][2]["status"], "APROVADO")

    def assinar(self, data_id, request_id="req-1", segredo="segredo"):
        ts = "1700000000"
        v1 = hmac.new(segredo.encode(), f"id:{data_id};request-id:{request_id};ts:{ts};".encode(), hashlib.sha256).hexdigest()
        return {"x-signature": f"ts={ts},v1={v1}", "x-request-id": request_id}

    def test_webhook_assinado_aprova_mesmo_sem_a_cobranca_na_memoria(self):
        # outra instância criou a cobrança: esta só recebeu o aviso
        resposta = app.app.test_client().post("/webhooks/mercadopago?data.id=987", json={"type": "payment"}, headers=self.assinar("987"))
        self.assertEqual(resposta.status_code, 200)
        self.assertEqual(app.produtor.enviados[-1][2]["status"], "APROVADO")
        self.assertEqual(app.produtor.enviados[-1][1], "5")

    def test_webhook_com_assinatura_errada_ou_ausente_e_recusado(self):
        cliente = app.app.test_client()
        self.assertEqual(cliente.post("/webhooks/mercadopago?data.id=987", json={}, headers=self.assinar("987", segredo="outro")).status_code, 401)
        self.assertEqual(cliente.post("/webhooks/mercadopago?data.id=987", json={}).status_code, 401)
        self.assertEqual(cliente.post("/webhooks/mercadopago", json={}, headers=self.assinar("987")).status_code, 401)  # sem data.id
        self.assertEqual(app.produtor.enviados, [])

    def test_webhook_de_pagamento_ainda_pendente_nao_aprova(self):
        self.mp.respostas[("GET", "/v1/payments/987")] = self.PAGAMENTO
        resposta = app.app.test_client().post("/webhooks/mercadopago?data.id=987", json={}, headers=self.assinar("987"))
        self.assertEqual(resposta.status_code, 200)
        self.assertEqual(app.produtor.enviados, [])

    def test_webhook_com_mercado_pago_fora_do_ar_pede_nova_tentativa(self):
        self.mp.respostas[("GET", "/v1/payments/987")] = pix.PixErro("Mercado Pago: URLError")
        resposta = app.app.test_client().post("/webhooks/mercadopago?data.id=987", json={}, headers=self.assinar("987"))
        self.assertEqual(resposta.status_code, 502)

    def test_sem_segredo_nada_e_aceito(self):
        self.assertFalse(pix.assinatura_valida("", "ts=1,v1=abc", "r", "987"))



if __name__ == "__main__":
    unittest.main()

"""Testes do Pagamentos, sem Kafka e sem esperar 3 s. Rode: python -m unittest -v (dentro de pagamentos-py/)."""
import json
import unittest

import app

app.ATRASO = 0  # nada de time.sleep(3) nos testes


class RegraDeNegocio(unittest.TestCase):
    def test_ate_100_aprova(self):
        self.assertEqual(app.decidir(17.0), ("APROVADO", None))
        self.assertEqual(app.decidir(100.0), ("APROVADO", None))

    def test_acima_de_100_recusa_com_motivo(self):
        self.assertEqual(app.decidir(100.01), ("RECUSADO", "limite do cartão de teste"))
        self.assertEqual(app.decidir(170.0), ("RECUSADO", "limite do cartão de teste"))


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


if __name__ == "__main__":
    unittest.main()

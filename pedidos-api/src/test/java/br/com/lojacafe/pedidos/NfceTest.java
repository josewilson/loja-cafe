package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

// Teste simples, sem subir o Spring nem falar com a Focus NFe: a chamada de rede é trocada por respostas prontas.
class NfceTest {

    /** Focus NFe sem internet: devolve as respostas na ordem e guarda o que foi pedido. */
    static class FocusFalsa extends Nfce {
        final List<Resposta> respostas = new ArrayList<>();
        final List<String> chamadas = new ArrayList<>();

        FocusFalsa(String ambiente) {
            super(JsonMapper.builder().build(), "focus", "token-de-teste", ambiente, "102", "0");
        }

        @Override
        protected Resposta chamar(String metodo, String caminho, String corpo) {
            chamadas.add(metodo + " " + caminho + (corpo == null ? "" : " " + corpo));
            return respostas.remove(0);
        }
    }

    private static Nfce nfce(String provedor, String token) {
        return new Nfce(JsonMapper.builder().build(), provedor, token, "homologacao", "102", "0");
    }

    private static Pedido pedido(FormaPagamento forma) {
        Produto produto = new Produto("Café coado", "", "☕", new BigDecimal("8.50"));
        ReflectionTestUtils.setField(produto, "id", 3L);
        Loja loja = new Loja("Matriz");
        ReflectionTestUtils.setField(loja, "id", 7L);
        Pedido p = new Pedido(new Usuario("Ana", "ana@exemplo.com", "hash", Papel.ATENDENTE, loja), null, forma, null, null,
                List.of(new ItemPedido(produto, 2, null, null)));
        ReflectionTestUtils.setField(p, "id", 12L);
        return p;
    }

    private static Loja lojaComCnpj() {
        Loja loja = new Loja("Matriz");
        loja.atualizarFiscal("Café Central LTDA", "11222333000181", null, null);
        return loja;
    }

    private static Map<Long, Produto> catalogo() {
        Produto produto = new Produto("Café coado", "", "☕", new BigDecimal("8.50"));
        produto.setFiscal("21011110", "5102");
        return Map.of(3L, produto);
    }

    @Test
    void configuracaoErradaNaoSobe() {
        assertThrows(IllegalStateException.class, () -> nfce("quase", ""));
        assertThrows(IllegalStateException.class, () -> nfce("focus", ""));            // real exige token
        assertThrows(IllegalStateException.class, () -> new Nfce(JsonMapper.builder().build(), "simulado", "", "talvez", "102", "0"));
        assertFalse(nfce("desligado", "").ligada());
        assertTrue(nfce("simulado", "").ligada());
    }

    @Test
    void simuladoAutorizaSemFalarComNinguem() {
        Nfce.Resultado r = nfce("simulado", "").emitir(pedido(FormaPagamento.PIX), lojaComCnpj(), catalogo());
        assertEquals("AUTORIZADA", r.status());
        assertEquals("12", r.numero());
        assertEquals(44, r.chave().length());
    }

    @Test
    void focusEnviaOsDadosFiscaisEGuardaANotaAutorizada() {
        var focus = new FocusFalsa("homologacao");
        focus.respostas.add(new Nfce.Resposta(201, Map.of("status", "autorizado", "numero", "55", "chave_nfe", "3526" + "0".repeat(40),
                "caminho_danfe", "/arquivos/nota.html")));
        Nfce.Resultado r = focus.emitir(pedido(FormaPagamento.PIX), lojaComCnpj(), catalogo());

        assertEquals("AUTORIZADA", r.status());
        assertEquals("55", r.numero());
        assertEquals("https://homologacao.focusnfe.com.br/arquivos/nota.html", r.url());
        String chamada = focus.chamadas.get(0);
        assertTrue(chamada.startsWith("POST /v2/nfce?ref=loja-cafe-12 "), chamada);
        for (String trecho : List.of("\"cnpj_emitente\":\"11222333000181\"", "\"codigo_ncm\":\"21011110\"", "\"cfop\":\"5102\"",
                "\"icms_situacao_tributaria\":\"102\"", "\"forma_pagamento\":\"17\"", "\"quantidade_comercial\":2", "\"valor_bruto\":17.00")) {
            assertTrue(chamada.contains(trecho), trecho + " faltou em " + chamada);
        }
    }

    @Test
    void cartaoVaiComoNaoIntegrado() {
        var focus = new FocusFalsa("homologacao");
        focus.respostas.add(new Nfce.Resposta(201, Map.of("status", "autorizado")));
        focus.emitir(pedido(FormaPagamento.CREDITO), lojaComCnpj(), catalogo());
        assertTrue(focus.chamadas.get(0).contains("\"forma_pagamento\":\"03\",\"valor_pagamento\":17.00,\"tipo_integracao\":\"2\""), focus.chamadas.get(0));
    }

    @Test
    void rejeicaoDaSefazViraNotaRejeitadaENaoErro() {
        var focus = new FocusFalsa("homologacao");
        focus.respostas.add(new Nfce.Resposta(201, Map.of("status", "erro_autorizacao", "mensagem_sefaz", "Rejeicao: NCM invalido")));
        Nfce.Resultado r = focus.emitir(pedido(FormaPagamento.PIX), lojaComCnpj(), catalogo());
        assertEquals("REJEITADA", r.status());
        assertEquals("Rejeicao: NCM invalido", r.mensagem());
    }

    @Test
    void referenciaJaUsadaConsultaANotaEmVezDeFicarPresa() {
        var focus = new FocusFalsa("homologacao");
        focus.respostas.add(new Nfce.Resposta(422, Map.of("mensagem", "ref ja existe")));
        focus.respostas.add(new Nfce.Resposta(200, Map.of("status", "autorizado", "numero", "56")));
        Nfce.Resultado r = focus.emitir(pedido(FormaPagamento.PIX), lojaComCnpj(), catalogo());
        assertEquals("AUTORIZADA", r.status());
        assertEquals("GET /v2/nfce/loja-cafe-12", focus.chamadas.get(1));
    }

    @Test
    void tokenErradoOuProvedorForaDoArViraBadGateway() {
        var focus = new FocusFalsa("homologacao");
        focus.respostas.add(new Nfce.Resposta(401, Map.of()));
        var erro = assertThrows(ResponseStatusException.class, () -> focus.emitir(pedido(FormaPagamento.PIX), lojaComCnpj(), catalogo()));
        assertEquals(502, erro.getStatusCode().value());
    }

    @Test
    void producaoUsaOutroEndereco() {
        var focus = new FocusFalsa("producao");
        focus.respostas.add(new Nfce.Resposta(201, Map.of("status", "autorizado", "caminho_danfe", "/n.html")));
        assertEquals("https://api.focusnfe.com.br/n.html", focus.emitir(pedido(FormaPagamento.PIX), lojaComCnpj(), catalogo()).url());
    }

    @Test
    void cancelamentoPrecisaSerConfirmadoPelaFocus() {
        var focus = new FocusFalsa("homologacao");
        focus.respostas.add(new Nfce.Resposta(200, Map.of("status", "cancelado")));
        focus.cancelar(pedido(FormaPagamento.PIX), "cliente desistiu da compra");
        assertTrue(focus.chamadas.get(0).startsWith("DELETE /v2/nfce/loja-cafe-12 "), focus.chamadas.get(0));

        focus.respostas.add(new Nfce.Resposta(200, Map.of("status", "erro_cancelamento", "mensagem_sefaz", "prazo vencido")));
        var erro = assertThrows(ResponseStatusException.class, () -> focus.cancelar(pedido(FormaPagamento.PIX), "cliente desistiu da compra"));
        assertEquals(409, erro.getStatusCode().value());
    }

    @Test
    void prazoParaCancelarSao30Minutos() {
        Pedido p = pedido(FormaPagamento.PIX);
        assertFalse(p.nfceCancelavel());                                   // sem nota
        p.registrarNfce(new Nfce.Resultado("AUTORIZADA", "1", "c", "u", "ok"));
        assertTrue(p.nfceCancelavel());
        ReflectionTestUtils.setField(p, "nfceEmitidaEm", java.time.Instant.now().minus(Nfce.PRAZO_CANCELAMENTO).minusSeconds(1));
        assertFalse(p.nfceCancelavel());                                   // passou de 30 minutos
    }
}

package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// Teste simples, sem subir o Spring: só a regra de negócio das entidades.
class PedidoTest {

    private static Produto produto(String nome, String preco) {
        Produto p = new Produto();
        ReflectionTestUtils.setField(p, "id", 1L);
        ReflectionTestUtils.setField(p, "nome", nome);
        ReflectionTestUtils.setField(p, "preco", new BigDecimal(preco));
        return p;
    }

    private static final Loja LOJA = lojaDeId(7L);
    private static final Usuario ANA = new Usuario("Ana", "ana@exemplo.com", "hash", Papel.ATENDENTE, LOJA);

    private static Loja lojaDeId(long id) {
        Loja l = new Loja("Matriz");
        ReflectionTestUtils.setField(l, "id", id);
        return l;
    }

    private static ItemPedido item(String preco, int qtd, Tamanho tamanho, List<Extra> extras) {
        return new ItemPedido(produto("Café coado", preco), qtd, tamanho, extras);
    }

    private Pedido novo() {
        return new Pedido(ANA, null, FormaPagamento.PIX, null, null, List.of(item("8.50", 2, null, null)));
    }

    private static void assertValor(String esperado, BigDecimal atual) {
        assertEquals(0, new BigDecimal(esperado).compareTo(atual), "valor era " + atual);
    }

    @Test
    void calculaValorENasceAguardando() {
        Pedido p = novo();
        assertValor("17.00", p.getValor());
        assertValor("8.50", p.getItens().get(0).getPrecoUnitario());
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, p.getStatus());
        assertEquals(Tamanho.MEDIO, p.getItens().get(0).getTamanho());
        assertEquals("Balcão", p.getCliente()); // sem nome do cliente, vale "Balcão"
        assertEquals("Ana", p.getAtendente());
        assertEquals(7L, p.getLojaId());
        assertNotNull(p.getCriadoEm());
    }

    @Test
    void tamanhoEExtrasMudamOPrecoUnitario() {
        // Cappuccino 12,00 + Grande 3,00 + Chantilly 2,00 + Canela 0,50 = 17,50; × 2 = 35,00
        var cappuccino = new ItemPedido(produto("Cappuccino", "12.00"), 2, Tamanho.GRANDE,
                List.of(Extra.CHANTILLY, Extra.CANELA));
        assertValor("17.50", cappuccino.getPrecoUnitario());
        assertValor("35.00", cappuccino.getValor());
        // Pequeno desconta R$ 1,00
        assertValor("11.00", new ItemPedido(produto("Cappuccino", "12.00"), 1, Tamanho.PEQUENO, null).getValor());
    }

    @Test
    void valorDoPedidoESomaDosItens() {
        Pedido p = new Pedido(ANA, null, FormaPagamento.DEBITO, null, "  sem açúcar ",
                List.of(item("8.50", 2, null, null), item("6.00", 1, Tamanho.GRANDE, List.of(Extra.CANELA))));
        // 2 × 8,50 = 17,00; 1 × (6,00 + 3,00 + 0,50) = 9,50
        assertValor("26.50", p.getValor());
        assertEquals(2, p.getItens().size());
        assertEquals("sem açúcar", p.getObservacao());
    }

    @Test
    void extraRepetidoContaUmaVezEObservacaoEmBrancoViraNula() {
        var repetido = item("8.00", 1, null, List.of(Extra.CHANTILLY, Extra.CHANTILLY));
        assertEquals(List.of(Extra.CHANTILLY), repetido.getExtras());
        assertValor("10.00", repetido.getValor());
        assertNull(new Pedido(ANA, null, FormaPagamento.PIX, null, "   ", List.of(repetido)).getObservacao());
    }

    @Test
    void parcelasSoExistemNoCredito() {
        assertEquals(1, new Pedido(ANA, null, FormaPagamento.PIX, 3, null, List.of(item("8.00", 1, null, null))).getParcelas());
        assertEquals(3, new Pedido(ANA, null, FormaPagamento.CREDITO, 3, null, List.of(item("8.00", 1, null, null))).getParcelas());
        assertEquals(1, new Pedido(ANA, null, FormaPagamento.CREDITO, null, null, List.of(item("8.00", 1, null, null))).getParcelas());
    }

    @Test
    void concluirAprovadoViraPago() {
        Pedido p = novo();
        assertTrue(p.concluir(true, null, "caixa-1"));
        assertEquals(StatusPedido.PAGO, p.getStatus());
        assertEquals("caixa-1", p.getProcessadoPor());
        assertNull(p.getMotivo());
    }

    @Test
    void concluirRecusadoGuardaMotivo() {
        Pedido p = novo();
        assertTrue(p.concluir(false, "limite do cartão de teste", "caixa-2"));
        assertEquals(StatusPedido.RECUSADO, p.getStatus());
        assertEquals("limite do cartão de teste", p.getMotivo());
        assertEquals("caixa-2", p.getProcessadoPor());
    }

    @Test
    void resultadoRepetidoEIgnorado() {
        Pedido p = novo();
        p.concluir(true, null, "caixa-1");
        // segundo resultado (evento repetido ou de outro caixa) não muda nada
        assertFalse(p.concluir(false, "limite do cartão de teste", "caixa-2"));
        assertEquals(StatusPedido.PAGO, p.getStatus());
        assertEquals("caixa-1", p.getProcessadoPor());
        assertNull(p.getMotivo());
    }

    // ---------- andamento na loja (cozinha, retirada, cancelamento) ----------

    private Pedido pago() {
        Pedido p = novo();
        p.concluir(true, null, "caixa-1");
        return p;
    }

    @Test
    void nasceNaFila() {
        assertEquals(Preparo.NA_FILA, novo().getPreparo());
    }

    @Test
    void avancaUmPassoPorVezEmPedidoPago() {
        Pedido p = pago();
        assertFalse(p.avancarPara(Preparo.PRONTO)); // não pula
        assertTrue(p.avancarPara(Preparo.PREPARANDO));
        assertFalse(p.avancarPara(Preparo.PREPARANDO)); // não repete
        assertTrue(p.avancarPara(Preparo.PRONTO));
        assertFalse(p.avancarPara(Preparo.NA_FILA)); // não volta
        assertTrue(p.avancarPara(Preparo.RETIRADO));
        assertEquals(Preparo.RETIRADO, p.getPreparo());
    }

    @Test
    void naoAvancaSemPagamentoAprovado() {
        Pedido aguardando = novo();
        assertFalse(aguardando.avancarPara(Preparo.PREPARANDO));
        Pedido recusado = novo();
        recusado.concluir(false, "limite", "caixa-1");
        assertFalse(recusado.avancarPara(Preparo.PREPARANDO));
    }

    @Test
    void cancelaNaFilaOuPreparandoEUmaSoVez() {
        Pedido p = pago();
        assertTrue(p.cancelar());
        assertEquals(Preparo.CANCELADO, p.getPreparo());
        assertFalse(p.cancelar()); // já cancelado
        Pedido preparando = pago();
        preparando.avancarPara(Preparo.PREPARANDO);
        assertTrue(preparando.cancelar());
    }

    @Test
    void naoCancelaProntoRetiradoNemRecusado() {
        Pedido pronto = pago();
        pronto.avancarPara(Preparo.PREPARANDO);
        pronto.avancarPara(Preparo.PRONTO);
        assertFalse(pronto.cancelar());
        pronto.avancarPara(Preparo.RETIRADO);
        assertFalse(pronto.cancelar());
        Pedido recusado = novo();
        recusado.concluir(false, "limite", "caixa-1");
        assertFalse(recusado.cancelar());
    }

    @Test
    void cancelarAntesDoPagamentoValeEOResultadoDepoisSoRegistra() {
        Pedido p = novo();
        assertTrue(p.cancelar());
        assertTrue(p.concluir(false, "limite", "caixa-1")); // o resultado ainda é registrado
        assertEquals(Preparo.CANCELADO, p.getPreparo()); // mas o pedido segue cancelado
    }

    @Test
    void pedidoQueJaEntrouNoFechamentoDeCaixaNaoCancela() {
        Pedido p = pago();
        ReflectionTestUtils.setField(p, "fechamentoId", 5L);
        assertFalse(p.cancelar());
        assertEquals(Preparo.NA_FILA, p.getPreparo());
    }
}

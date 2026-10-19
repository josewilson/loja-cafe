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

    private static final Usuario ANA = new Usuario("Ana", "ana@exemplo.com", "hash");

    private static ItemPedido item(String preco, int qtd, Tamanho tamanho, List<Extra> extras) {
        return new ItemPedido(produto("Café coado", preco), qtd, tamanho, extras);
    }

    private Pedido novo() {
        return new Pedido(ANA, FormaPagamento.PIX, null, null, List.of(item("8.50", 2, null, null)));
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
        assertEquals("Ana", p.getCliente());
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
        Pedido p = new Pedido(ANA, FormaPagamento.DEBITO, null, "  sem açúcar ",
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
        assertNull(new Pedido(ANA, FormaPagamento.PIX, null, "   ", List.of(repetido)).getObservacao());
    }

    @Test
    void parcelasSoExistemNoCredito() {
        assertEquals(1, new Pedido(ANA, FormaPagamento.PIX, 3, null, List.of(item("8.00", 1, null, null))).getParcelas());
        assertEquals(3, new Pedido(ANA, FormaPagamento.CREDITO, 3, null, List.of(item("8.00", 1, null, null))).getParcelas());
        assertEquals(1, new Pedido(ANA, FormaPagamento.CREDITO, null, null, List.of(item("8.00", 1, null, null))).getParcelas());
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
}

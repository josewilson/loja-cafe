package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// Teste simples, sem subir o Spring: só a regra de negócio da entidade.
class PedidoTest {

    private static Produto produto(String nome, String preco) {
        Produto p = new Produto();
        ReflectionTestUtils.setField(p, "nome", nome);
        ReflectionTestUtils.setField(p, "preco", new BigDecimal(preco));
        return p;
    }

    private Pedido novo() {
        return new Pedido("Ana", produto("Café coado", "8.50"), 2, null, null, null);
    }

    private static void assertValor(String esperado, BigDecimal atual) {
        assertEquals(0, new BigDecimal(esperado).compareTo(atual), "valor era " + atual);
    }

    @Test
    void calculaValorENasceAguardando() {
        Pedido p = novo();
        assertValor("17.00", p.getValor());
        assertValor("8.50", p.getPrecoUnitario());
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, p.getStatus());
        assertEquals(Tamanho.MEDIO, p.getTamanho());
        assertNotNull(p.getCriadoEm());
    }

    @Test
    void tamanhoEExtrasMudamOPrecoUnitario() {
        // Cappuccino 12,00 + Grande 3,00 + Chantilly 2,00 + Canela 0,50 = 17,50; × 2 = 35,00
        Pedido p = new Pedido("Ana", produto("Cappuccino", "12.00"), 2, Tamanho.GRANDE,
                List.of(Extra.CHANTILLY, Extra.CANELA), "sem açúcar");
        assertValor("17.50", p.getPrecoUnitario());
        assertValor("35.00", p.getValor());
        assertEquals("sem açúcar", p.getObservacao());
        // Pequeno desconta R$ 1,00
        assertValor("11.00", new Pedido("Ana", produto("Cappuccino", "12.00"), 1, Tamanho.PEQUENO, null, null).getValor());
    }

    @Test
    void extraRepetidoContaUmaVezEObservacaoEmBrancoViraNula() {
        Pedido p = new Pedido("  Ana ", produto("Café", "8.00"), 1, null,
                List.of(Extra.CHANTILLY, Extra.CHANTILLY), "   ");
        assertEquals(List.of(Extra.CHANTILLY), p.getExtras());
        assertValor("10.00", p.getValor());
        assertNull(p.getObservacao());
        assertEquals("Ana", p.getCliente());
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

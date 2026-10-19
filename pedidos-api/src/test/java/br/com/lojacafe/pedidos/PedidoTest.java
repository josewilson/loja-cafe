package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

// Teste simples, sem Spring: só a regra de negócio da entidade.
class PedidoTest {

    private Pedido novo() {
        return new Pedido("Ana", "Café coado", 2, new BigDecimal("8.50"));
    }

    @Test
    void calculaValorENasceAguardando() {
        Pedido p = novo();
        assertEquals(0, new BigDecimal("17.00").compareTo(p.getValor()));
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, p.getStatus());
        assertNotNull(p.getCriadoEm());
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

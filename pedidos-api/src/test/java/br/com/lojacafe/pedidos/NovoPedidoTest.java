package br.com.lojacafe.pedidos;

import br.com.lojacafe.pedidos.PedidoController.NovoPedido;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

// Valida o corpo do POST /pedidos (specs/api.md) direto com o Validator, sem Spring.
class NovoPedidoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static NovoPedido.Item item(Long produtoId, int quantidade) {
        return new NovoPedido.Item(produtoId, quantidade, null, null);
    }

    private static NovoPedido pedido(NovoPedido.Item... itens) {
        return new NovoPedido(null, FormaPagamento.PIX, null, null, List.of(itens));
    }

    /** Nomes dos campos que a validação rejeitou (itens[0].quantidade vira "itens[0].quantidade"). */
    private Set<String> erros(NovoPedido p) {
        return validator.validate(p).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void pedidoValidoPassa() {
        assertTrue(erros(pedido(item(1L, 2))).isEmpty());
        var completo = new NovoPedido(null, FormaPagamento.CREDITO, 3, "sem açúcar",
                List.of(new NovoPedido.Item(2L, 1, Tamanho.GRANDE, List.of(Extra.CANELA)), item(1L, 1)));
        assertTrue(erros(completo).isEmpty());
    }

    @Test
    void quantidadeAceitaDe1a50() {
        assertTrue(erros(pedido(item(1L, 1))).isEmpty());
        assertTrue(erros(pedido(item(1L, 50))).isEmpty());
        assertEquals(Set.of("itens[0].quantidade"), erros(pedido(item(1L, 0))));
        assertEquals(Set.of("itens[0].quantidade"), erros(pedido(item(1L, 51))));
    }

    @Test
    void formaDePagamentoEProdutoSaoObrigatorios() {
        assertEquals(Set.of("formaPagamento"), erros(new NovoPedido(null, null, null, null, List.of(item(1L, 1)))));
        assertEquals(Set.of("itens[0].produtoId"), erros(pedido(item(null, 1))));
    }

    @Test
    void de1a10Itens() {
        assertEquals(Set.of("itens"), erros(new NovoPedido(null, FormaPagamento.PIX, null, null, List.of())));
        assertEquals(Set.of("itens"), erros(new NovoPedido(null, FormaPagamento.PIX, null, null, null)));
        assertTrue(erros(new NovoPedido(null, FormaPagamento.PIX, null, null, Collections.nCopies(10, item(1L, 1)))).isEmpty());
        assertEquals(Set.of("itens"), erros(new NovoPedido(null, FormaPagamento.PIX, null, null, Collections.nCopies(11, item(1L, 1)))));
    }

    @Test
    void parcelasDe1a3() {
        assertEquals(Set.of("parcelas"), erros(new NovoPedido(null, FormaPagamento.CREDITO, 0, null, List.of(item(1L, 1)))));
        assertEquals(Set.of("parcelas"), erros(new NovoPedido(null, FormaPagamento.CREDITO, 4, null, List.of(item(1L, 1)))));
    }

    @Test
    void clienteDoBalcaoAte60Caracteres() {
        var itens = List.of(item(1L, 1));
        assertTrue(erros(new NovoPedido("A".repeat(60), FormaPagamento.PIX, null, null, itens)).isEmpty());
        assertEquals(Set.of("cliente"), erros(new NovoPedido("A".repeat(61), FormaPagamento.PIX, null, null, itens)));
    }

    @Test
    void limiteDaObservacao() {
        assertTrue(erros(new NovoPedido(null, FormaPagamento.PIX, null, "x".repeat(140), List.of(item(1L, 1)))).isEmpty());
        assertEquals(Set.of("observacao"),
                erros(new NovoPedido(null, FormaPagamento.PIX, null, "x".repeat(141), List.of(item(1L, 1)))));
    }

    @Test
    void ateCincoExtrasPorItem() {
        var cinco = Collections.nCopies(5, Extra.CANELA);
        var seis = Collections.nCopies(6, Extra.CANELA);
        assertTrue(erros(pedido(new NovoPedido.Item(1L, 1, null, cinco))).isEmpty());
        assertEquals(Set.of("itens[0].extras"), erros(pedido(new NovoPedido.Item(1L, 1, null, seis))));
    }
}

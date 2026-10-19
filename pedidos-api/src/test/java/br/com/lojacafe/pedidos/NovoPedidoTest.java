package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

// Valida o corpo do POST /pedidos (specs/api.md) direto com o Validator, sem Spring.
class NovoPedidoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static NovoPedido pedido(String cliente, Long produtoId, int quantidade) {
        return new NovoPedido(cliente, produtoId, quantidade, null, null, null);
    }

    /** Nomes dos campos que a validação rejeitou. */
    private Set<String> erros(NovoPedido p) {
        return validator.validate(p).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void pedidoValidoPassa() {
        assertTrue(erros(pedido("Ana", 1L, 2)).isEmpty());
        assertTrue(erros(new NovoPedido("Ana", 2L, 1, Tamanho.GRANDE, List.of(Extra.CANELA), "sem açúcar")).isEmpty());
    }

    @Test
    void quantidadeAceitaDe1a50() {
        assertTrue(erros(pedido("Ana", 1L, 1)).isEmpty());
        assertTrue(erros(pedido("Ana", 1L, 50)).isEmpty());
        assertEquals(Set.of("quantidade"), erros(pedido("Ana", 1L, 0)));
        assertEquals(Set.of("quantidade"), erros(pedido("Ana", 1L, 51)));
    }

    @Test
    void clienteEProdutoSaoObrigatorios() {
        assertEquals(Set.of("cliente"), erros(pedido("", 1L, 1)));
        assertEquals(Set.of("cliente"), erros(pedido(null, 1L, 1)));
        assertEquals(Set.of("cliente"), erros(pedido("  ", 1L, 1)));
        assertEquals(Set.of("produtoId"), erros(pedido("Ana", null, 1)));
    }

    @Test
    void limitesDeTamanhoDosTextos() {
        assertTrue(erros(pedido("A".repeat(60), 1L, 1)).isEmpty());
        assertEquals(Set.of("cliente"), erros(pedido("A".repeat(61), 1L, 1)));
        assertTrue(erros(new NovoPedido("Ana", 1L, 1, null, null, "x".repeat(140))).isEmpty());
        assertEquals(Set.of("observacao"), erros(new NovoPedido("Ana", 1L, 1, null, null, "x".repeat(141))));
    }

    @Test
    void ateCincoExtras() {
        var cinco = List.of(Extra.CANELA, Extra.CANELA, Extra.CANELA, Extra.CANELA, Extra.CANELA);
        var seis = List.of(Extra.CANELA, Extra.CANELA, Extra.CANELA, Extra.CANELA, Extra.CANELA, Extra.CANELA);
        assertTrue(erros(new NovoPedido("Ana", 1L, 1, null, cinco, null)).isEmpty());
        assertEquals(Set.of("extras"), erros(new NovoPedido("Ana", 1L, 1, null, seis, null)));
    }

    @Test
    void variosErrosDeUmaVez() {
        assertEquals(Set.of("cliente", "quantidade", "produtoId"), erros(pedido("", null, 0)));
    }
}

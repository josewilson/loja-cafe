package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

// Valida o corpo do POST /pedidos (specs/api.md) direto com o Validator, sem Spring.
class NovoPedidoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static NovoPedido pedido(String cliente, String item, int quantidade, String preco) {
        return new NovoPedido(cliente, item, quantidade, preco == null ? null : new BigDecimal(preco));
    }

    /** Nomes dos campos que a validação rejeitou. */
    private Set<String> erros(NovoPedido p) {
        return validator.validate(p).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void pedidoValidoPassa() {
        assertTrue(erros(pedido("Ana", "Café coado", 2, "8.50")).isEmpty());
    }

    @Test
    void quantidadeAceitaDe1a50() {
        assertTrue(erros(pedido("Ana", "Café", 1, "8.50")).isEmpty());
        assertTrue(erros(pedido("Ana", "Café", 50, "8.50")).isEmpty());
        assertEquals(Set.of("quantidade"), erros(pedido("Ana", "Café", 0, "8.50")));
        assertEquals(Set.of("quantidade"), erros(pedido("Ana", "Café", 51, "8.50")));
    }

    @Test
    void clienteEItemSaoObrigatorios() {
        assertEquals(Set.of("cliente"), erros(pedido("", "Café", 1, "8.50")));
        assertEquals(Set.of("cliente"), erros(pedido(null, "Café", 1, "8.50")));
        assertEquals(Set.of("item"), erros(pedido("Ana", "  ", 1, "8.50")));
    }

    @Test
    void precoPrecisaSerMaiorQueZero() {
        assertEquals(Set.of("precoUnitario"), erros(pedido("Ana", "Café", 1, "0")));
        assertEquals(Set.of("precoUnitario"), erros(pedido("Ana", "Café", 1, "-1")));
        assertEquals(Set.of("precoUnitario"), erros(pedido("Ana", "Café", 1, null)));
    }

    @Test
    void variosErrosDeUmaVez() {
        assertEquals(Set.of("cliente", "quantidade", "precoUnitario"), erros(pedido("", "Café", 0, "-1")));
    }
}

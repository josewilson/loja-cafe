package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// Só a conta do consumo de insumos (sem Spring e sem banco): ficha × tamanho × quantidade, com extras.
class ConsumoInsumosTest {

    private static final long LEITE = 1L;
    private static final long CHANTILLY = 2L;

    private static ItemPedido item(long produtoId, int quantidade, Tamanho tamanho, List<Extra> extras) {
        Produto p = new Produto();
        ReflectionTestUtils.setField(p, "id", produtoId);
        ReflectionTestUtils.setField(p, "nome", "Produto " + produtoId);
        ReflectionTestUtils.setField(p, "preco", new BigDecimal("10.00"));
        return new ItemPedido(p, quantidade, tamanho, extras);
    }

    private static Map<Long, BigDecimal> calcular(List<ItemPedido> itens) {
        return ConsumoInsumos.calcular(itens,
                // produto 7 gasta 150 de leite; produto 8 não tem ficha
                produtoId -> produtoId == 7L ? List.of(new FichaProduto(7L, LEITE, new BigDecimal("150.000"))) : List.of(),
                // chantilly gasta 20 de chantilly; os outros extras não têm ficha
                extra -> extra == Extra.CHANTILLY ? List.of(new FichaExtra(Extra.CHANTILLY, CHANTILLY, new BigDecimal("20.000"))) : List.of());
    }

    private static void assertQtd(String esperado, Map<Long, BigDecimal> consumo, long insumo) {
        assertEquals(0, new BigDecimal(esperado).compareTo(consumo.get(insumo)), "consumo era " + consumo.get(insumo));
    }

    @Test
    void multiplicaPelaQuantidade() {
        assertQtd("300.000", calcular(List.of(item(7, 2, Tamanho.MEDIO, null))), LEITE);
    }

    @Test
    void tamanhoMudaOConsumo() {
        assertQtd("112.500", calcular(List.of(item(7, 1, Tamanho.PEQUENO, null))), LEITE); // 150 × 0,75
        assertQtd("150.000", calcular(List.of(item(7, 1, Tamanho.MEDIO, null))), LEITE); // 1×
        assertQtd("450.000", calcular(List.of(item(7, 2, Tamanho.GRANDE, null))), LEITE); // 2 × 1,5×
    }

    @Test
    void extrasSomamOConsumoDeSuaFicha() {
        var consumo = calcular(List.of(item(7, 2, Tamanho.GRANDE, List.of(Extra.CHANTILLY, Extra.CANELA))));
        assertQtd("450.000", consumo, LEITE);
        assertQtd("60.000", consumo, CHANTILLY); // 20 × 2 × 1,5
        assertEquals(2, consumo.size()); // a canela não tem ficha: não aparece
    }

    @Test
    void itensDoMesmoProdutoSeSomam() {
        var consumo = calcular(List.of(item(7, 1, Tamanho.MEDIO, null), item(7, 2, Tamanho.PEQUENO, null)));
        assertQtd("375.000", consumo, LEITE); // 150 + 2 × 150 × 0,75
    }

    @Test
    void produtoSemFichaNaoGastaNada() {
        assertTrue(calcular(List.of(item(8, 5, Tamanho.GRANDE, null))).isEmpty());
        assertTrue(calcular(List.of(item(8, 1, Tamanho.MEDIO, List.of(Extra.CANELA)))).isEmpty());
    }

    @Test
    void arredondaParaTresCasas() {
        var consumo = ConsumoInsumos.calcular(List.of(item(9, 1, Tamanho.PEQUENO, null)),
                produtoId -> List.of(new FichaProduto(9L, LEITE, new BigDecimal("1.001"))), extra -> List.of());
        assertQtd("0.751", consumo, LEITE); // 1,001 × 0,75 = 0,75075
        assertEquals(3, consumo.get(LEITE).scale());
    }
}

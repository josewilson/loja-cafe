package br.com.lojacafe.pedidos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Baixa os insumos de um pedido e devolve quando preciso (specs/api.md, "Insumos e ficha técnica").
 * Não bloqueia a venda: se o insumo faltar, o estoque fica negativo e o alerta avisa.
 * Roda dentro da transação de quem chama; quem chama garante que cada devolução acontece uma única vez.
 */
@Component
public class ConsumoInsumos {

    private final FichaProdutoRepository fichasProduto;
    private final FichaExtraRepository fichasExtra;
    private final InsumoRepository insumos;
    private final EstoqueInsumoRepository estoque;
    private final ConsumoPedidoRepository consumos;

    public ConsumoInsumos(FichaProdutoRepository fichasProduto, FichaExtraRepository fichasExtra, InsumoRepository insumos,
                          EstoqueInsumoRepository estoque, ConsumoPedidoRepository consumos) {
        this.fichasProduto = fichasProduto;
        this.fichasExtra = fichasExtra;
        this.insumos = insumos;
        this.estoque = estoque;
        this.consumos = consumos;
    }

    /**
     * Consumo total por insumo dos itens de um pedido:
     * quantidade × fator do tamanho × (ficha do produto + ficha de cada extra). Separado do banco para poder ser testado sozinho.
     */
    static Map<Long, BigDecimal> calcular(List<ItemPedido> itens, Function<Long, List<FichaProduto>> fichaDoProduto,
                                          Function<Extra, List<FichaExtra>> fichaDoExtra) {
        Map<Long, BigDecimal> total = new LinkedHashMap<>();
        Map<Long, List<FichaProduto>> produtos = new HashMap<>();
        Map<Extra, List<FichaExtra>> extras = new EnumMap<>(Extra.class);
        for (ItemPedido item : itens) {
            BigDecimal peso = BigDecimal.valueOf(item.getQuantidade()).multiply(item.getTamanho().getFatorInsumo());
            for (FichaProduto f : produtos.computeIfAbsent(item.getProdutoId(), fichaDoProduto)) {
                total.merge(f.getId().insumoId(), f.getQuantidade().multiply(peso), BigDecimal::add);
            }
            for (Extra extra : item.getExtras()) {
                for (FichaExtra f : extras.computeIfAbsent(extra, fichaDoExtra)) {
                    total.merge(f.getId().insumoId(), f.getQuantidade().multiply(peso), BigDecimal::add);
                }
            }
        }
        total.replaceAll((insumo, quantidade) -> quantidade.setScale(3, RoundingMode.HALF_UP));
        return total;
    }

    /** Baixa o estoque da loja do pedido e guarda o que foi consumido (só de insumos ativos). */
    public void registrarEBaixar(Pedido pedido) {
        Map<Long, BigDecimal> consumo = calcular(pedido.getItens(), fichasProduto::findByIdProdutoId, fichasExtra::findByIdExtra);
        if (consumo.isEmpty()) {
            return; // nenhum produto do pedido tem ficha: nada a controlar
        }
        Set<Long> ativos = insumos.findAllById(consumo.keySet()).stream()
                .filter(Insumo::isAtivo).map(Insumo::getId).collect(Collectors.toSet());
        consumo.forEach((insumoId, quantidade) -> {
            if (ativos.contains(insumoId)) {
                consumos.save(new ConsumoPedido(pedido.getId(), insumoId, quantidade));
                estoque.baixar(pedido.getLojaId(), insumoId, quantidade);
            }
        });
    }

    /** Devolve ao estoque da loja o que o pedido consumiu (recusa, ou cancelamento ainda na fila). */
    public void devolver(Pedido pedido) {
        for (ConsumoPedido c : consumos.findByIdPedidoId(pedido.getId())) {
            estoque.repor(pedido.getLojaId(), c.getId().insumoId(), c.getQuantidade());
        }
    }
}

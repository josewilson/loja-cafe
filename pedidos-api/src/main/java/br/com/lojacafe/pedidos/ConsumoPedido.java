package br.com.lojacafe.pedidos;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** O que um pedido consumiu de um insumo (uma "foto": a ficha pode mudar depois). Serve para devolver. */
@Entity
@Table(name = "consumo_pedido")
public class ConsumoPedido {

    @Embeddable
    public record Id(Long pedidoId, Long insumoId) {}

    @EmbeddedId
    private Id id;
    @Column(precision = 14, scale = 3)
    private BigDecimal quantidade;

    protected ConsumoPedido() {
        // exigido pelo JPA
    }

    public ConsumoPedido(Long pedidoId, Long insumoId, BigDecimal quantidade) {
        this.id = new Id(pedidoId, insumoId);
        this.quantidade = quantidade;
    }

    public Id getId() { return id; }
    public BigDecimal getQuantidade() { return quantidade; }
}

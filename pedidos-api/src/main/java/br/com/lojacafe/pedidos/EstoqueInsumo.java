package br.com.lojacafe.pedidos;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Estoque de um insumo numa loja. A quantidade pode ficar negativa: a venda nunca é bloqueada, só gera alerta. */
@Entity
@Table(name = "estoque_insumo")
public class EstoqueInsumo {

    @Embeddable
    public record Id(Long lojaId, Long insumoId) {}

    @EmbeddedId
    private Id id;
    @Column(precision = 14, scale = 3)
    private BigDecimal quantidade;
    @Column(precision = 14, scale = 3)
    private BigDecimal minimo;

    protected EstoqueInsumo() {
        // exigido pelo JPA
    }

    public EstoqueInsumo(Long lojaId, Long insumoId, BigDecimal quantidade, BigDecimal minimo) {
        this.id = new Id(lojaId, insumoId);
        this.quantidade = quantidade;
        this.minimo = minimo;
    }

    public Id getId() { return id; }
    public BigDecimal getQuantidade() { return quantidade; }
    public void setQuantidade(BigDecimal quantidade) { this.quantidade = quantidade; }
    public BigDecimal getMinimo() { return minimo; }
    public void setMinimo(BigDecimal minimo) { this.minimo = minimo; }
}

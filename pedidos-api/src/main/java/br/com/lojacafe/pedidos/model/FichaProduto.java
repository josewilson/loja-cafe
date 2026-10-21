package br.com.lojacafe.pedidos.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Quanto de um insumo gasta 1 unidade de um produto, para o tamanho MÉDIO. */
@Entity
@Table(name = "ficha_produto")
public class FichaProduto {

    @Embeddable
    public record Id(Long produtoId, Long insumoId) {}

    @EmbeddedId
    private Id id;
    @Column(precision = 12, scale = 3)
    private BigDecimal quantidade;

    protected FichaProduto() {
        // exigido pelo JPA
    }

    public FichaProduto(Long produtoId, Long insumoId, BigDecimal quantidade) {
        this.id = new Id(produtoId, insumoId);
        this.quantidade = quantidade;
    }

    public Id getId() { return id; }
    public BigDecimal getQuantidade() { return quantidade; }
}

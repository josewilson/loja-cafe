package br.com.lojacafe.pedidos.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Quanto de um insumo gasta 1 unidade de um extra (ex.: Chantilly). */
@Entity
@Table(name = "ficha_extra")
public class FichaExtra {

    @Embeddable
    public record Id(@Enumerated(EnumType.STRING) Extra extra, Long insumoId) {}

    @EmbeddedId
    private Id id;
    @Column(precision = 12, scale = 3)
    private BigDecimal quantidade;

    protected FichaExtra() {
        // exigido pelo JPA
    }

    public FichaExtra(Extra extra, Long insumoId, BigDecimal quantidade) {
        this.id = new Id(extra, insumoId);
        this.quantidade = quantidade;
    }

    public Id getId() { return id; }
    public BigDecimal getQuantidade() { return quantidade; }
}

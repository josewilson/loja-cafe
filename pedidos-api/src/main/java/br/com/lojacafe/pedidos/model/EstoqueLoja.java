package br.com.lojacafe.pedidos.model;

import jakarta.persistence.*;

/** Quantas unidades de um produto uma loja tem. */
@Entity
@Table(name = "estoque_loja")
public class EstoqueLoja {

    @Embeddable
    public record Id(Long lojaId, Long produtoId) {}

    @EmbeddedId
    private Id id;
    private int quantidade;

    protected EstoqueLoja() {
        // exigido pelo JPA
    }

    public EstoqueLoja(Long lojaId, Long produtoId, int quantidade) {
        this.id = new Id(lojaId, produtoId);
        this.quantidade = quantidade;
    }

    public Id getId() { return id; }
    public int getQuantidade() { return quantidade; }
    public void setQuantidade(int quantidade) { this.quantidade = quantidade; }
}

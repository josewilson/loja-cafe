package br.com.lojacafe.pedidos;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Item do cardápio da rede. O preço que vale é este, nunca o que vem do cliente. O estoque é de cada loja (EstoqueLoja). */
@Entity
public class Produto {

    @Id
    private Long id;
    private String nome;
    private String descricao;
    private String emoji;
    @Column(precision = 10, scale = 2)
    private BigDecimal preco;

    protected Produto() {
        // exigido pelo JPA
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getDescricao() { return descricao; }
    public String getEmoji() { return emoji; }
    public BigDecimal getPreco() { return preco; }
}

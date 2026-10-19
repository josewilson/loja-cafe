package br.com.lojacafe.pedidos;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Item do cardápio. O preço que vale é este, nunca o que vem do cliente. */
@Entity
public class Produto {

    @Id
    private Long id;
    private String nome;
    private String descricao;
    private String emoji;
    @Column(precision = 10, scale = 2)
    private BigDecimal preco;
    // Unidades disponíveis. Só muda pelos métodos atômicos do ProdutoRepository.
    private Integer estoque;

    protected Produto() {
        // exigido pelo JPA
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getDescricao() { return descricao; }
    public String getEmoji() { return emoji; }
    public BigDecimal getPreco() { return preco; }
    public Integer getEstoque() { return estoque; }
}

package br.com.lojacafe.pedidos;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Item do cardápio da rede. O preço que vale é este, nunca o que vem do cliente. O estoque é de cada loja (EstoqueLoja). */
@Entity
public class Produto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private String descricao;
    private String emoji;
    @Column(precision = 10, scale = 2)
    private BigDecimal preco;
    // Desativado: some do balcão e não aceita pedido novo, mas fica nos pedidos e relatórios antigos.
    private boolean ativo = true;

    protected Produto() {
        // exigido pelo JPA
    }

    public Produto(String nome, String descricao, String emoji, BigDecimal preco) {
        atualizar(nome, descricao, emoji, preco);
    }

    /** O preço novo vale só para pedidos futuros: cada pedido guarda o preço que usou. */
    public void atualizar(String nome, String descricao, String emoji, BigDecimal preco) {
        this.nome = nome.trim();
        this.descricao = descricao == null || descricao.isBlank() ? null : descricao.trim();
        this.emoji = emoji.trim();
        this.preco = preco;
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getDescricao() { return descricao; }
    public String getEmoji() { return emoji; }
    public BigDecimal getPreco() { return preco; }
    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean ativo) { this.ativo = ativo; }
}

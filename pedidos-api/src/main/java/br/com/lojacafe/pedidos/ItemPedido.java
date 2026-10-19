package br.com.lojacafe.pedidos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

/** Uma linha do pedido: produto, tamanho, extras e quantidade. O preço é calculado aqui, no servidor. */
@Entity
public class ItemPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long id;
    private Long produtoId;
    private String item;
    private int quantidade;
    @Enumerated(EnumType.STRING)
    private Tamanho tamanho;
    // BatchSize: carrega os extras de vários itens de uma vez, não uma consulta por item.
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @BatchSize(size = 100)
    private List<Extra> extras = new ArrayList<>();
    @Column(precision = 10, scale = 2)
    private BigDecimal precoUnitario;
    @Column(precision = 10, scale = 2)
    private BigDecimal valor;

    protected ItemPedido() {
        // exigido pelo JPA
    }

    /** precoUnitario = preço do cardápio + ajuste do tamanho + extras; valor = quantidade × precoUnitario. */
    public ItemPedido(Produto produto, int quantidade, Tamanho tamanho, List<Extra> extras) {
        this.produtoId = produto.getId();
        this.item = produto.getNome();
        this.quantidade = quantidade;
        this.tamanho = tamanho == null ? Tamanho.MEDIO : tamanho;
        // distinct(): extra repetido conta uma vez só.
        this.extras = extras == null ? new ArrayList<>() : new ArrayList<>(extras.stream().distinct().toList());
        BigDecimal unitario = produto.getPreco().add(this.tamanho.getAjuste());
        for (Extra extra : this.extras) {
            unitario = unitario.add(extra.getPreco());
        }
        this.precoUnitario = unitario;
        this.valor = unitario.multiply(BigDecimal.valueOf(quantidade));
    }

    public Long getProdutoId() { return produtoId; }
    public String getItem() { return item; }
    public int getQuantidade() { return quantidade; }
    public Tamanho getTamanho() { return tamanho; }
    public List<Extra> getExtras() { return extras; }
    public BigDecimal getPrecoUnitario() { return precoUnitario; }
    public BigDecimal getValor() { return valor; }
}

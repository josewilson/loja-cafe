package br.com.lojacafe.pedidos.model;

import java.math.BigDecimal;

/** Adicional opcional: cada um soma seu preço ao preço unitário. */
public enum Extra {
    LEITE_VEGETAL("Leite vegetal", new BigDecimal("2.00")),
    CHANTILLY("Chantilly", new BigDecimal("2.00")),
    CARAMELO("Calda de caramelo", new BigDecimal("1.50")),
    CANELA("Canela", new BigDecimal("0.50"));

    private final String nome;
    private final BigDecimal preco;

    Extra(String nome, BigDecimal preco) {
        this.nome = nome;
        this.preco = preco;
    }

    public String getNome() { return nome; }
    public BigDecimal getPreco() { return preco; }
}

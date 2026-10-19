package br.com.lojacafe.pedidos;

import java.math.BigDecimal;

/** Tamanho do pedido: ajusta o preço unitário (MEDIO é o preço do cardápio). */
public enum Tamanho {
    PEQUENO("Pequeno", new BigDecimal("-1.00")),
    MEDIO("Médio", new BigDecimal("0.00")),
    GRANDE("Grande", new BigDecimal("3.00"));

    private final String nome;
    private final BigDecimal ajuste;

    Tamanho(String nome, BigDecimal ajuste) {
        this.nome = nome;
        this.ajuste = ajuste;
    }

    public String getNome() { return nome; }
    public BigDecimal getAjuste() { return ajuste; }
}

package br.com.lojacafe.pedidos.model;

import java.math.BigDecimal;

/** Tamanho do pedido: ajusta o preço unitário (MEDIO é o preço do cardápio). */
public enum Tamanho {
    // fatorInsumo: quanto de insumo gasta, em relação ao tamanho Médio (a ficha técnica é escrita para o Médio).
    PEQUENO("Pequeno", new BigDecimal("-1.00"), new BigDecimal("0.75")),
    MEDIO("Médio", new BigDecimal("0.00"), new BigDecimal("1.00")),
    GRANDE("Grande", new BigDecimal("3.00"), new BigDecimal("1.50"));

    private final String nome;
    private final BigDecimal ajuste;
    private final BigDecimal fatorInsumo;

    Tamanho(String nome, BigDecimal ajuste, BigDecimal fatorInsumo) {
        this.nome = nome;
        this.ajuste = ajuste;
        this.fatorInsumo = fatorInsumo;
    }

    public String getNome() { return nome; }
    public BigDecimal getAjuste() { return ajuste; }
    public BigDecimal getFatorInsumo() { return fatorInsumo; }
}

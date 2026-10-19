package br.com.lojacafe.pedidos;

import java.math.BigDecimal;

/** Linha do cardápio da rede com o estoque da loja de quem pediu (GET /produtos). */
public record ProdutoComEstoque(Long id, String nome, String descricao, String emoji, BigDecimal preco, Integer estoque) {}

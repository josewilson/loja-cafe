package br.com.lojacafe.pedidos;

import java.math.BigDecimal;

/** Linha do catálogo de insumos com o estoque e o mínimo de uma loja (GET /insumos). */
public record InsumoComEstoque(Long id, String nome, UnidadeInsumo unidade, boolean ativo, BigDecimal quantidade, BigDecimal minimo) {}

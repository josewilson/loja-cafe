package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Corpo do POST /pedidos (specs/api.md). O preço NÃO vem do cliente: a API calcula. */
public record NovoPedido(
        @Size(max = 60) String cliente,
        @NotNull FormaPagamento formaPagamento,
        @Min(1) @Max(3) Integer parcelas,
        @Size(max = 140) String observacao,
        @NotEmpty @Size(max = 10) List<@NotNull @Valid Item> itens) {

    public record Item(
            @NotNull Long produtoId,
            @Min(1) @Max(50) int quantidade,
            Tamanho tamanho,
            @Size(max = 5) List<Extra> extras) {
    }
}

package br.com.lojacafe.pedidos;

import jakarta.validation.constraints.*;
import java.util.List;

/** Corpo do POST /pedidos (specs/api.md). O preço NÃO vem do cliente: a API calcula. */
public record NovoPedido(
        @NotBlank @Size(max = 60) String cliente,
        @NotNull Long produtoId,
        @Min(1) @Max(50) int quantidade,
        Tamanho tamanho,
        @Size(max = 5) List<Extra> extras,
        @Size(max = 140) String observacao) {
}

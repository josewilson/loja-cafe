package br.com.lojacafe.pedidos;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Corpo do POST /pedidos (specs/api.md). */
public record NovoPedido(
        @NotBlank String cliente,
        @NotBlank String item,
        @Min(1) @Max(50) int quantidade,
        @NotNull @Positive BigDecimal precoUnitario) {
}

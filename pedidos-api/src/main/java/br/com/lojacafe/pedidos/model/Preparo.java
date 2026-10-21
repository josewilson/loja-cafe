package br.com.lojacafe.pedidos.model;

/** Andamento do pedido na loja (specs/api.md). Independe do status, que é só o pagamento. */
public enum Preparo {
    NA_FILA,
    PREPARANDO,
    PRONTO,
    RETIRADO,
    CANCELADO;

    /** De qual etapa se chega a esta (só avança um passo por vez). Nulo se não se chega por avanço. */
    public Preparo anterior() {
        return switch (this) {
            case PREPARANDO -> NA_FILA;
            case PRONTO -> PREPARANDO;
            case RETIRADO -> PRONTO;
            default -> null;
        };
    }
}

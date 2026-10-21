package br.com.lojacafe.pedidos.model;

/** Papel do usuário na rede (specs/api.md). Só o ADMIN não pertence a uma loja. */
public enum Papel {
    ADMIN,
    GERENTE,
    ATENDENTE,
    COZINHA
}

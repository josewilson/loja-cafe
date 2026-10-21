package br.com.lojacafe.pedidos.model;

import jakarta.persistence.*;

/** Matéria-prima da rede (grão, leite, copo). O estoque é de cada loja (EstoqueInsumo). */
@Entity
public class Insumo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    @Enumerated(EnumType.STRING)
    private UnidadeInsumo unidade;
    // Desativado: deixa de ser consumido e de alertar.
    private boolean ativo = true;

    protected Insumo() {
        // exigido pelo JPA
    }

    public Insumo(String nome, UnidadeInsumo unidade) {
        this.nome = nome.trim();
        this.unidade = unidade;
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome.trim(); }
    public UnidadeInsumo getUnidade() { return unidade; }
    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean ativo) { this.ativo = ativo; }
}

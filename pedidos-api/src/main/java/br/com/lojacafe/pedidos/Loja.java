package br.com.lojacafe.pedidos;

import jakarta.persistence.*;

/** Uma loja da rede. */
@Entity
public class Loja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private boolean ativa = true;

    protected Loja() {
        // exigido pelo JPA
    }

    public Loja(String nome) {
        this.nome = nome.trim();
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public boolean isAtiva() { return ativa; }
}

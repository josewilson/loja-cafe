package br.com.lojacafe.pedidos;

import jakarta.persistence.*;
import java.time.Instant;

/** Conta de um funcionário. Só guarda o hash BCrypt da senha, nunca a senha. */
@Entity
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    @Column(unique = true)
    private String email;
    private String senhaHash;
    @Enumerated(EnumType.STRING)
    private Papel papel;
    // Nulo só para o ADMIN (a rede toda).
    @ManyToOne(fetch = FetchType.EAGER)
    private Loja loja;
    private boolean ativo = true;
    // Fase 8b: hash do token de "nova senha" (uso único) e até quando vale.
    private String redefinicaoHash;
    private Instant redefinicaoExpiraEm;

    protected Usuario() {
        // exigido pelo JPA
    }

    public Usuario(String nome, String email, String senhaHash, Papel papel, Loja loja) {
        this.nome = nome.trim();
        this.email = email.trim().toLowerCase();
        this.senhaHash = senhaHash;
        this.papel = papel;
        this.loja = loja;
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getEmail() { return email; }
    public String getSenhaHash() { return senhaHash; }
    public Papel getPapel() { return papel; }
    public Loja getLoja() { return loja; }
    public Long getLojaId() { return loja == null ? null : loja.getId(); }
    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean ativo) { this.ativo = ativo; }

    /** Guarda um novo token de redefinição (o anterior deixa de valer). */
    public void permitirRedefinicao(String tokenHash, Instant expiraEm) {
        this.redefinicaoHash = tokenHash;
        this.redefinicaoExpiraEm = expiraEm;
    }

    public boolean redefinicaoValida() { return redefinicaoHash != null && redefinicaoExpiraEm.isAfter(Instant.now()); }

    /** Troca a senha e gasta o token. */
    public void redefinirSenha(String novoHash) {
        this.senhaHash = novoHash;
        this.redefinicaoHash = null;
        this.redefinicaoExpiraEm = null;
    }
}

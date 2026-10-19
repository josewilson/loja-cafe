package br.com.lojacafe.pedidos;

import jakarta.persistence.*;
import java.time.Instant;

/** Login ativo. O id é o SHA-256 do token: quem lê o banco não consegue usar a sessão. */
@Entity
public class Sessao {

    @Id
    private String tokenHash;
    @ManyToOne(optional = false)
    private Usuario usuario;
    private Instant expiraEm;

    protected Sessao() {
        // exigido pelo JPA
    }

    public Sessao(String tokenHash, Usuario usuario, Instant expiraEm) {
        this.tokenHash = tokenHash;
        this.usuario = usuario;
        this.expiraEm = expiraEm;
    }

    public Usuario getUsuario() { return usuario; }
    public Instant getExpiraEm() { return expiraEm; }
}

package br.com.lojacafe.pedidos.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Uma linha do registro de auditoria: quem fez o quê, onde e quando. Só se acrescenta; nunca se altera. */
@Entity
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant quando;
    private Long usuarioId;
    private Long lojaId;
    private String acao;
    private String entidade;
    private String entidadeId;
    private String detalhe;

    protected Auditoria() {
        // exigido pelo JPA
    }

    public Auditoria(Long usuarioId, Long lojaId, String acao, String entidade, String entidadeId, String detalhe) {
        this.quando = Instant.now();
        this.usuarioId = usuarioId;
        this.lojaId = lojaId;
        this.acao = acao;
        this.entidade = entidade;
        this.entidadeId = entidadeId;
        this.detalhe = detalhe == null || detalhe.length() <= 500 ? detalhe : detalhe.substring(0, 500);
    }

    public Long getId() { return id; }
    public Instant getQuando() { return quando; }
    public Long getUsuarioId() { return usuarioId; }
    public Long getLojaId() { return lojaId; }
    public String getAcao() { return acao; }
    public String getEntidade() { return entidade; }
    public String getEntidadeId() { return entidadeId; }
    public String getDetalhe() { return detalhe; }
}

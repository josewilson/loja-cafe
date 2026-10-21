package br.com.lojacafe.pedidos.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Fechamento de caixa de um turno: o que foi vendido, o dinheiro esperado e o contado (specs/api.md). Só se acrescenta. */
@Entity
@Table(name = "fechamento_caixa")
public class FechamentoCaixa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long lojaId;
    private Long usuarioId;
    private Instant desde;
    private Instant ate;
    private int pedidos;
    @Column(precision = 12, scale = 2)
    private BigDecimal total;
    @Column(precision = 12, scale = 2)
    private BigDecimal dinheiroEsperado;
    @Column(precision = 12, scale = 2)
    private BigDecimal dinheiroContado;
    @Column(precision = 12, scale = 2)
    private BigDecimal diferenca;
    private String observacao;
    private Instant criadoEm;

    protected FechamentoCaixa() {
        // exigido pelo JPA
    }

    /** Nasce vazio: os pedidos são ligados a ele e só depois os totais são calculados (completar). */
    public FechamentoCaixa(Long lojaId, Long usuarioId, Instant desde, BigDecimal dinheiroContado, String observacao) {
        this.lojaId = lojaId;
        this.usuarioId = usuarioId;
        this.desde = desde;
        this.ate = Instant.now();
        this.criadoEm = this.ate;
        this.pedidos = 0;
        this.total = BigDecimal.ZERO;
        this.dinheiroEsperado = BigDecimal.ZERO;
        this.dinheiroContado = dinheiroContado;
        this.diferenca = dinheiroContado;
        this.observacao = observacao == null || observacao.isBlank() ? null : observacao.trim();
    }

    /** Grava os totais do turno. diferenca = contado − esperado (negativa = faltou dinheiro). */
    public void completar(int pedidos, BigDecimal total, BigDecimal dinheiroEsperado) {
        this.pedidos = pedidos;
        this.total = total;
        this.dinheiroEsperado = dinheiroEsperado;
        this.diferenca = dinheiroContado.subtract(dinheiroEsperado);
    }

    public Long getId() { return id; }
    public Long getLojaId() { return lojaId; }
    public Long getUsuarioId() { return usuarioId; }
    public Instant getDesde() { return desde; }
    public Instant getAte() { return ate; }
    public int getPedidos() { return pedidos; }
    public BigDecimal getTotal() { return total; }
    public BigDecimal getDinheiroEsperado() { return dinheiroEsperado; }
    public BigDecimal getDinheiroContado() { return dinheiroContado; }
    public BigDecimal getDiferenca() { return diferenca; }
    public String getObservacao() { return observacao; }
    public Instant getCriadoEm() { return criadoEm; }
}

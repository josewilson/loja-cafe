package br.com.lojacafe.pedidos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

@Entity
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // Dono do pedido (quem fez login). Nulo só nos pedidos anteriores ao login.
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Usuario usuario;
    private String cliente;
    @Enumerated(EnumType.STRING)
    private FormaPagamento formaPagamento;
    private Integer parcelas;
    // BatchSize: a lista de pedidos busca os itens em lotes, não uma consulta por pedido.
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "pedido_id")
    @BatchSize(size = 100)
    private List<ItemPedido> itens = new ArrayList<>();
    private String observacao;
    @Column(precision = 10, scale = 2)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    private StatusPedido status;
    private String motivo;
    private String processadoPor;
    private Instant criadoEm;

    protected Pedido() {
        // exigido pelo JPA
    }

    /** valor = soma dos itens (cada item já vem calculado). Parcelas só existem no crédito. */
    public Pedido(Usuario usuario, FormaPagamento forma, Integer parcelas, String observacao, List<ItemPedido> itens) {
        this.usuario = usuario;
        this.cliente = usuario.getNome();
        this.formaPagamento = forma;
        this.parcelas = forma == FormaPagamento.CREDITO && parcelas != null ? parcelas : 1;
        this.itens = new ArrayList<>(itens);
        this.observacao = observacao == null || observacao.isBlank() ? null : observacao.trim();
        this.valor = itens.stream().map(ItemPedido::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.status = StatusPedido.AGUARDANDO_PAGAMENTO;
        this.criadoEm = Instant.now();
    }

    /** Registra a decisão do Pagamentos. Só muda quem ainda está aguardando. */
    public boolean concluir(boolean aprovado, String motivo, String instancia) {
        if (status != StatusPedido.AGUARDANDO_PAGAMENTO) {
            return false;
        }
        this.status = aprovado ? StatusPedido.PAGO : StatusPedido.RECUSADO;
        this.motivo = motivo;
        this.processadoPor = instancia;
        return true;
    }

    public Long getId() { return id; }
    public String getCliente() { return cliente; }
    public FormaPagamento getFormaPagamento() { return formaPagamento; }
    public Integer getParcelas() { return parcelas; }
    public List<ItemPedido> getItens() { return itens; }
    public String getObservacao() { return observacao; }
    public BigDecimal getValor() { return valor; }
    public StatusPedido getStatus() { return status; }
    public String getMotivo() { return motivo; }
    public String getProcessadoPor() { return processadoPor; }
    public Instant getCriadoEm() { return criadoEm; }
}

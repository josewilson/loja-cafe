package br.com.lojacafe.pedidos;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String cliente;
    private String item;
    private int quantidade;
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    private StatusPedido status;
    private String motivo;
    private String processadoPor;
    private Instant criadoEm;

    protected Pedido() {
        // exigido pelo JPA
    }

    public Pedido(String cliente, String item, int quantidade, BigDecimal precoUnitario) {
        this.cliente = cliente;
        this.item = item;
        this.quantidade = quantidade;
        this.valor = precoUnitario.multiply(BigDecimal.valueOf(quantidade));
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
    public String getItem() { return item; }
    public int getQuantidade() { return quantidade; }
    public BigDecimal getValor() { return valor; }
    public StatusPedido getStatus() { return status; }
    public String getMotivo() { return motivo; }
    public String getProcessadoPor() { return processadoPor; }
    public Instant getCriadoEm() { return criadoEm; }
}

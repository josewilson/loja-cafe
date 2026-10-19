package br.com.lojacafe.pedidos;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {
    List<Pedido> findByLojaIdOrderByIdDesc(Long lojaId, Pageable limite);

    List<Pedido> findAllByOrderByIdDesc(Pageable limite);

    /** A fila da cozinha: do mais antigo ao mais novo. */
    List<Pedido> findByLojaIdAndStatusAndPreparoInOrderByIdAsc(Long lojaId, StatusPedido status, Collection<Preparo> preparos, Pageable limite);

    // ---------- fechamento de caixa (turno aberto = pedidos PAGO, não cancelados, sem fechamento) ----------

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Pedido p set p.fechamentoId = :fechamentoId where p.lojaId = :lojaId and p.status = :pago "
            + "and p.preparo <> :cancelado and p.fechamentoId is null")
    int fechar(Long lojaId, Long fechamentoId, StatusPedido pago, Preparo cancelado);

    /** Linhas {forma, quantidade de pedidos, soma dos valores} do turno aberto. */
    @Query("select p.formaPagamento, count(p), sum(p.valor) from Pedido p where p.lojaId = :lojaId and p.status = :pago "
            + "and p.preparo <> :cancelado and p.fechamentoId is null group by p.formaPagamento")
    List<Object[]> turnoAbertoPorForma(Long lojaId, StatusPedido pago, Preparo cancelado);

    /** Linhas {forma, quantidade de pedidos, soma dos valores} de um fechamento já feito. */
    @Query("select p.formaPagamento, count(p), sum(p.valor) from Pedido p where p.fechamentoId = :fechamentoId group by p.formaPagamento")
    List<Object[]> doFechamentoPorForma(Long fechamentoId);

    long countByLojaIdAndStatus(Long lojaId, StatusPedido status);

    // ---------- relatório de vendas de um dia ([de, ate) pela hora de criação) ----------

    @Query("select p.formaPagamento, count(p), sum(p.valor) from Pedido p where p.lojaId = :lojaId and p.criadoEm >= :de and p.criadoEm < :ate "
            + "and p.status = :pago and p.preparo <> :cancelado group by p.formaPagamento")
    List<Object[]> vendasPorForma(Long lojaId, Instant de, Instant ate, StatusPedido pago, Preparo cancelado);

    /** Linhas {item, quantidade vendida, soma dos valores}, do que mais saiu para o que menos saiu. */
    @Query("select i.item, sum(i.quantidade), sum(i.valor) from Pedido p join p.itens i where p.lojaId = :lojaId "
            + "and p.criadoEm >= :de and p.criadoEm < :ate and p.status = :pago and p.preparo <> :cancelado "
            + "group by i.item order by sum(i.quantidade) desc, i.item")
    List<Object[]> maisVendidos(Long lojaId, Instant de, Instant ate, StatusPedido pago, Preparo cancelado, Pageable limite);

    long countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndStatus(Long lojaId, Instant de, Instant ate, StatusPedido status);

    long countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndPreparo(Long lojaId, Instant de, Instant ate, Preparo preparo);

    /**
     * Lê o pedido travando a linha até o fim da transação. Quem muda o andamento (cozinha, cancelamento) e quem registra o
     * resultado do pagamento passam por aqui, para nunca decidirem em cima de um dado velho (ex.: devolver o estoque duas vezes).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Pedido> findWithLockById(Long id);
}

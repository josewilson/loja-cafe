package br.com.lojacafe.pedidos;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {
    List<Pedido> findByLojaIdOrderByIdDesc(Long lojaId, Pageable limite);

    List<Pedido> findAllByOrderByIdDesc(Pageable limite);

    /** A fila da cozinha: do mais antigo ao mais novo. */
    List<Pedido> findByLojaIdAndStatusAndPreparoInOrderByIdAsc(Long lojaId, StatusPedido status, Collection<Preparo> preparos, Pageable limite);

    /**
     * Lê o pedido travando a linha até o fim da transação. Quem muda o andamento (cozinha, cancelamento) e quem registra o
     * resultado do pagamento passam por aqui, para nunca decidirem em cima de um dado velho (ex.: devolver o estoque duas vezes).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Pedido> findWithLockById(Long id);
}

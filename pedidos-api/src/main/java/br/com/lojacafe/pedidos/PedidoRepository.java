package br.com.lojacafe.pedidos;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {
    List<Pedido> findByLojaIdOrderByIdDesc(Long lojaId, Pageable limite);

    List<Pedido> findAllByOrderByIdDesc(Pageable limite);
}

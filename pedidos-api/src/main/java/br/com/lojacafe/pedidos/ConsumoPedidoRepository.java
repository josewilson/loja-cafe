package br.com.lojacafe.pedidos;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumoPedidoRepository extends JpaRepository<ConsumoPedido, ConsumoPedido.Id> {
    List<ConsumoPedido> findByIdPedidoId(Long pedidoId);
}

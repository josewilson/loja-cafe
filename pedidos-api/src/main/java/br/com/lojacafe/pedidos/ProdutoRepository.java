package br.com.lojacafe.pedidos;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {
    List<Produto> findAllByOrderByIdAsc();

    /** Baixa o estoque numa única instrução: dois pedidos ao mesmo tempo não vendem além do que existe. Devolve 0 se não há o bastante. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Produto p set p.estoque = p.estoque - :qtd where p.id = :id and p.estoque >= :qtd")
    int baixar(Long id, int qtd);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Produto p set p.estoque = p.estoque + :qtd where p.id = :id")
    int repor(Long id, int qtd);
}

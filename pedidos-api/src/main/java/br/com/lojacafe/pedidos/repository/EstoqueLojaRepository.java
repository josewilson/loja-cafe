package br.com.lojacafe.pedidos.repository;

import br.com.lojacafe.pedidos.model.EstoqueLoja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EstoqueLojaRepository extends JpaRepository<EstoqueLoja, EstoqueLoja.Id> {

    /** Baixa o estoque numa única instrução: dois pedidos ao mesmo tempo não vendem além do que existe. Devolve 0 se não há o bastante. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EstoqueLoja e set e.quantidade = e.quantidade - :qtd "
            + "where e.id.lojaId = :lojaId and e.id.produtoId = :produtoId and e.quantidade >= :qtd")
    int baixar(Long lojaId, Long produtoId, int qtd);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EstoqueLoja e set e.quantidade = e.quantidade + :qtd where e.id.lojaId = :lojaId and e.id.produtoId = :produtoId")
    int repor(Long lojaId, Long produtoId, int qtd);
}

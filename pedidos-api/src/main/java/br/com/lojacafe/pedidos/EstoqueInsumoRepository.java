package br.com.lojacafe.pedidos;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EstoqueInsumoRepository extends JpaRepository<EstoqueInsumo, EstoqueInsumo.Id> {

    /** Baixa numa só instrução (vendas simultâneas não se atropelam). Sem bloqueio: a quantidade pode ficar negativa. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EstoqueInsumo e set e.quantidade = e.quantidade - :qtd where e.id.lojaId = :lojaId and e.id.insumoId = :insumoId")
    int baixar(Long lojaId, Long insumoId, BigDecimal qtd);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EstoqueInsumo e set e.quantidade = e.quantidade + :qtd where e.id.lojaId = :lojaId and e.id.insumoId = :insumoId")
    int repor(Long lojaId, Long insumoId, BigDecimal qtd);

    /** Catálogo de insumos com o estoque de uma loja (insumo sem linha de estoque conta como 0). */
    @Query("select new br.com.lojacafe.pedidos.InsumoComEstoque(i.id, i.nome, i.unidade, i.ativo, coalesce(e.quantidade, 0bd), coalesce(e.minimo, 0bd)) "
            + "from Insumo i left join EstoqueInsumo e on e.id.insumoId = i.id and e.id.lojaId = :lojaId order by i.id")
    List<InsumoComEstoque> catalogo(Long lojaId);
}

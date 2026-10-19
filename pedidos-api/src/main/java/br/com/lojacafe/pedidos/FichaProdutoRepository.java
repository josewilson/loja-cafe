package br.com.lojacafe.pedidos;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FichaProdutoRepository extends JpaRepository<FichaProduto, FichaProduto.Id> {
    List<FichaProduto> findByIdProdutoId(Long produtoId);

    /** Apaga na hora, antes de gravar a ficha nova: o Hibernate faz INSERT antes de DELETE e bateria na chave primária. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FichaProduto f where f.id.produtoId = :produtoId")
    int apagarDoProduto(Long produtoId);
}

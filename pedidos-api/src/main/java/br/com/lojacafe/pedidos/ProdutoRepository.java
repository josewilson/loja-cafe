package br.com.lojacafe.pedidos;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {

    /** Cardápio da rede (só produtos ativos) com o estoque de uma loja (produto sem linha de estoque conta como 0). */
    List<Produto> findAllByOrderByIdAsc();

    Optional<Produto> findByNomeIgnoreCase(String nome);

    @Query("select new br.com.lojacafe.pedidos.ProdutoComEstoque(p.id, p.nome, p.descricao, p.emoji, p.preco, coalesce(e.quantidade, 0)) "
            + "from Produto p left join EstoqueLoja e on e.id.produtoId = p.id and e.id.lojaId = :lojaId where p.ativo = true order by p.id")
    List<ProdutoComEstoque> cardapio(Long lojaId);
}

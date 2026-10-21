package br.com.lojacafe.pedidos.repository;

import br.com.lojacafe.pedidos.model.EstoqueLoja;
import br.com.lojacafe.pedidos.model.Produto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {

    /** Linha do cardápio da rede com o estoque da loja de quem pediu (GET /produtos). */
    record ProdutoComEstoque(Long id, String nome, String descricao, String emoji, BigDecimal preco, Integer estoque) {}

    /** Cardápio da rede (só produtos ativos) com o estoque de uma loja (produto sem linha de estoque conta como 0). */
    List<Produto> findAllByOrderByIdAsc();

    Optional<Produto> findByNomeIgnoreCase(String nome);

    @Query("select new br.com.lojacafe.pedidos.repository.ProdutoRepository$ProdutoComEstoque(p.id, p.nome, p.descricao, p.emoji, p.preco, coalesce(e.quantidade, 0)) "
            + "from Produto p left join EstoqueLoja e on e.id.produtoId = p.id and e.id.lojaId = :lojaId where p.ativo = true order by p.id")
    List<ProdutoComEstoque> cardapio(Long lojaId);
}

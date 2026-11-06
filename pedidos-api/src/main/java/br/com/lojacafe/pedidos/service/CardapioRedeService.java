package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.EstoqueLoja;
import br.com.lojacafe.pedidos.model.Loja;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Produto;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.AuditoriaRepository;
import br.com.lojacafe.pedidos.repository.EstoqueLojaRepository;
import br.com.lojacafe.pedidos.repository.LojaRepository;
import br.com.lojacafe.pedidos.repository.ProdutoRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cardápio da rede (specs/api.md): só o ADMIN edita, porque o preço muda em todas as lojas. */
@Service
public class CardapioRedeService {

    public record DadosProduto(@NotBlank @Size(max = 60) String nome,
                               @Size(max = 140) String descricao,
                               @NotBlank @Size(max = 16) String emoji,
                               @NotNull @DecimalMin("0.01") @DecimalMax("10000.00") @Digits(integer = 5, fraction = 2) BigDecimal preco,
                               Boolean ativo,
                               @Pattern(regexp = "\\d{8}", message = "NCM tem 8 dígitos") String ncm,
                               @Pattern(regexp = "\\d{4}", message = "CFOP tem 4 dígitos") String cfop) {}
    public record ProdutoVista(Long id, String nome, String descricao, String emoji, BigDecimal preco, boolean ativo,
                               String ncm, String cfop) {
        static ProdutoVista de(Produto p) {
            return new ProdutoVista(p.getId(), p.getNome(), p.getDescricao(), p.getEmoji(), p.getPreco(), p.isAtivo(), p.getNcm(), p.getCfop());
        }
    }

    private final ProdutoRepository produtos;
    private final LojaRepository lojas;
    private final EstoqueLojaRepository estoque;
    private final AuditoriaRepository auditor;

    public CardapioRedeService(ProdutoRepository produtos, LojaRepository lojas, EstoqueLojaRepository estoque, AuditoriaRepository auditor) {
        this.produtos = produtos;
        this.lojas = lojas;
        this.estoque = estoque;
        this.auditor = auditor;
    }

    /** Todos os produtos, inclusive os desativados. */
    public List<ProdutoVista> listar() {
        return produtos.findAllByOrderByIdAsc().stream().map(ProdutoVista::de).toList();
    }
    @Transactional
    public ProdutoVista criar(Usuario quem, DadosProduto dados) {
        if (produtos.findByNomeIgnoreCase(dados.nome().trim()).isPresent()) {
            throw Acesso.nomeRepetido("produto");
        }
        Produto novo = new Produto(dados.nome(), dados.descricao(), dados.emoji(), dados.preco());
        novo.setFiscal(dados.ncm(), dados.cfop());
        Produto produto = produtos.save(novo);
        // Produto novo nasce sem estoque em todas as lojas; cada gerente ajusta o da sua.
        for (Loja loja : lojas.findAll()) {
            estoque.save(new EstoqueLoja(loja.getId(), produto.getId(), 0));
        }
        auditor.registrar(quem, null, "PRODUTO_CRIADO", "Produto", produto.getId(), produto.getNome() + ", preço " + produto.getPreco());
        return ProdutoVista.de(produto);
    }
    @Transactional
    public ProdutoVista alterar(Usuario quem, Long id,
                                DadosProduto dados) {
        Produto produto = produtos.findById(id)
                .orElseThrow(() -> Acesso.naoExiste("Produto"));
        produtos.findByNomeIgnoreCase(dados.nome().trim()).filter(outro -> !outro.getId().equals(id)).ifPresent(outro -> {
            throw Acesso.nomeRepetido("produto");
        });

        // Só o que mudou vai para a auditoria (ex.: "preço 12.00 -> 13.00").
        List<String> mudancas = new ArrayList<>();
        if (!produto.getNome().equals(dados.nome().trim())) {
            mudancas.add("nome " + produto.getNome() + " -> " + dados.nome().trim());
        }
        if (produto.getPreco().compareTo(dados.preco()) != 0) {
            mudancas.add("preço " + produto.getPreco() + " -> " + dados.preco());
        }
        boolean ativo = dados.ativo() == null ? produto.isAtivo() : dados.ativo();
        if (ativo != produto.isAtivo()) {
            mudancas.add("ativo " + produto.isAtivo() + " -> " + ativo);
        }

        produto.atualizar(dados.nome(), dados.descricao(), dados.emoji(), dados.preco());
        produto.setAtivo(ativo);
        produto.setFiscal(dados.ncm(), dados.cfop());
        produtos.save(produto);
        if (!mudancas.isEmpty()) {
            auditor.registrar(quem, null, "PRODUTO_ALTERADO", "Produto", id, String.join("; ", mudancas));
        }
        return ProdutoVista.de(produto);
    }
}

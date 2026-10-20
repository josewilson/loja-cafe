package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Cardápio da rede (specs/api.md): só o ADMIN edita, porque o preço muda em todas as lojas. */
@RestController
@RequestMapping("/cardapio")
public class CardapioRedeController {

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
    private final Auditor auditor;

    public CardapioRedeController(ProdutoRepository produtos, LojaRepository lojas, EstoqueLojaRepository estoque, Auditor auditor) {
        this.produtos = produtos;
        this.lojas = lojas;
        this.estoque = estoque;
        this.auditor = auditor;
    }

    /** Todos os produtos, inclusive os desativados. */
    @GetMapping
    @Papeis(Papel.ADMIN)
    public List<ProdutoVista> listar() {
        return produtos.findAllByOrderByIdAsc().stream().map(ProdutoVista::de).toList();
    }

    @PostMapping
    @Papeis(Papel.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProdutoVista criar(@RequestAttribute(Autenticacao.USUARIO) Usuario quem, @Valid @RequestBody DadosProduto dados) {
        if (produtos.findByNomeIgnoreCase(dados.nome().trim()).isPresent()) {
            throw nomeRepetido();
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

    @PutMapping("/{id}")
    @Papeis(Papel.ADMIN)
    @Transactional
    public ProdutoVista alterar(@RequestAttribute(Autenticacao.USUARIO) Usuario quem, @PathVariable Long id,
                                @Valid @RequestBody DadosProduto dados) {
        Produto produto = produtos.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não existe"));
        produtos.findByNomeIgnoreCase(dados.nome().trim()).filter(outro -> !outro.getId().equals(id)).ifPresent(outro -> {
            throw nomeRepetido();
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

    private static ResponseStatusException nomeRepetido() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um produto com este nome");
    }
}

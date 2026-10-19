package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Lojas da rede e o estoque de cada uma (specs/api.md). */
@RestController
public class LojaController {

    public record NovaLoja(@NotBlank @Size(max = 120) String nome, @Min(0) @Max(10000) Integer estoqueInicial) {}
    public record LojaVista(Long id, String nome, boolean ativa) {
        static LojaVista de(Loja l) {
            return new LojaVista(l.getId(), l.getNome(), l.isAtiva());
        }
    }
    public record NovaQuantidade(@NotNull @Min(0) @Max(100000) Integer quantidade) {}
    public record EstoqueVista(Long lojaId, Long produtoId, int quantidade) {}

    private final LojaRepository lojas;
    private final ProdutoRepository produtos;
    private final EstoqueLojaRepository estoque;
    private final Auditor auditor;

    public LojaController(LojaRepository lojas, ProdutoRepository produtos, EstoqueLojaRepository estoque, Auditor auditor) {
        this.lojas = lojas;
        this.produtos = produtos;
        this.estoque = estoque;
        this.auditor = auditor;
    }

    @GetMapping("/lojas")
    @Papeis(Papel.ADMIN)
    public List<LojaVista> listar() {
        return lojas.findAll().stream().map(LojaVista::de).toList();
    }

    @PostMapping("/lojas")
    @Papeis(Papel.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LojaVista criar(@RequestAttribute(Autenticacao.USUARIO) Usuario quem, @Valid @RequestBody NovaLoja nova) {
        if (lojas.findByNome(nova.nome().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma loja com este nome");
        }
        Loja loja = lojas.save(new Loja(nova.nome()));
        int inicial = nova.estoqueInicial() == null ? 0 : nova.estoqueInicial();
        for (Produto p : produtos.findAll()) {
            estoque.save(new EstoqueLoja(loja.getId(), p.getId(), inicial));
        }
        auditor.registrar(quem, loja.getId(), "LOJA_CRIADA", "Loja", loja.getId(), "nome " + loja.getNome() + ", estoque inicial " + inicial);
        return LojaVista.de(loja);
    }

    @PutMapping("/lojas/{lojaId}/estoque/{produtoId}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    @Transactional
    public EstoqueVista ajustarEstoque(@RequestAttribute(Autenticacao.USUARIO) Usuario quem,
                                       @PathVariable Long lojaId, @PathVariable Long produtoId,
                                       @Valid @RequestBody NovaQuantidade corpo) {
        Autenticacao.escopo(quem, lojaId); // gerente só mexe na própria loja
        if (!lojas.existsById(lojaId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não existe");
        }
        Produto produto = produtos.findById(produtoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não existe"));
        var chave = new EstoqueLoja.Id(lojaId, produtoId);
        EstoqueLoja linha = estoque.findById(chave).orElseGet(() -> new EstoqueLoja(lojaId, produtoId, 0));
        int antes = linha.getQuantidade();
        linha.setQuantidade(corpo.quantidade());
        estoque.save(linha);
        auditor.registrar(quem, lojaId, "ESTOQUE_AJUSTADO", "Produto", produtoId,
                produto.getNome() + ": " + antes + " -> " + corpo.quantidade());
        return new EstoqueVista(lojaId, produtoId, corpo.quantidade());
    }
}

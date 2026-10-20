package br.com.lojacafe.pedidos;

import br.com.lojacafe.pedidos.AuthController.Papeis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Lojas da rede e o estoque de cada uma (specs/api.md). */
@RestController
public class LojaController {

    public record NovaLoja(@NotBlank @Size(max = 120) String nome, @Min(0) @Max(10000) Integer estoqueInicial,
                           @Size(max = 120) String razaoSocial, @Pattern(regexp = "\\d{14}", message = "CNPJ tem 14 dígitos") String cnpj,
                           @Size(max = 20) String inscricaoEstadual, @Size(max = 200) String endereco) {}
    public record DadosFiscais(@Size(max = 120) String razaoSocial, @Pattern(regexp = "\\d{14}", message = "CNPJ tem 14 dígitos") String cnpj,
                               @Size(max = 20) String inscricaoEstadual, @Size(max = 200) String endereco) {}
    public record LojaVista(Long id, String nome, boolean ativa, String razaoSocial, String cnpj, String inscricaoEstadual, String endereco) {
        static LojaVista de(Loja l) {
            return new LojaVista(l.getId(), l.getNome(), l.isAtiva(), l.getRazaoSocial(), l.getCnpj(), l.getInscricaoEstadual(), l.getEndereco());
        }
    }
    public record NovaQuantidade(@NotNull @Min(0) @Max(100000) Integer quantidade) {}
    public record EstoqueVista(Long lojaId, Long produtoId, int quantidade) {}

    private final LojaRepository lojas;
    private final ProdutoRepository produtos;
    private final EstoqueLojaRepository estoque;
    private final AuditoriaRepository auditor;
    private final InsumoRepository insumos;
    private final EstoqueInsumoRepository estoqueInsumos;

    public LojaController(LojaRepository lojas, ProdutoRepository produtos, EstoqueLojaRepository estoque, AuditoriaRepository auditor,
                          InsumoRepository insumos, EstoqueInsumoRepository estoqueInsumos) {
        this.insumos = insumos;
        this.estoqueInsumos = estoqueInsumos;
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
    public LojaVista criar(@RequestAttribute(AuthController.USUARIO) Usuario quem, @Valid @RequestBody NovaLoja nova) {
        if (lojas.findByNome(nova.nome().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma loja com este nome");
        }
        conferirCnpj(nova.cnpj());
        Loja novaLoja = new Loja(nova.nome());
        novaLoja.atualizarFiscal(nova.razaoSocial(), nova.cnpj(), nova.inscricaoEstadual(), nova.endereco());
        Loja loja = lojas.save(novaLoja);
        int inicial = nova.estoqueInicial() == null ? 0 : nova.estoqueInicial();
        for (Produto p : produtos.findAll()) {
            estoque.save(new EstoqueLoja(loja.getId(), p.getId(), inicial));
        }
        // E a loja nova também começa com a linha de estoque (zerada) de cada insumo.
        for (Insumo insumo : insumos.findAll()) {
            estoqueInsumos.save(new EstoqueInsumo(loja.getId(), insumo.getId(), BigDecimal.ZERO, BigDecimal.ZERO));
        }
        auditor.registrar(quem, loja.getId(), "LOJA_CRIADA", "Loja", loja.getId(), "nome " + loja.getNome() + ", estoque inicial " + inicial);
        return LojaVista.de(loja);
    }

    /** Só os dados fiscais mudam aqui; o nome da loja não. Campo ausente = apagado. */
    @PutMapping("/lojas/{lojaId}")
    @Papeis(Papel.ADMIN)
    @Transactional
    public LojaVista alterarFiscal(@RequestAttribute(AuthController.USUARIO) Usuario quem, @PathVariable Long lojaId,
                                   @Valid @RequestBody DadosFiscais dados) {
        Loja loja = lojas.findById(lojaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não existe"));
        conferirCnpj(dados.cnpj());
        loja.atualizarFiscal(dados.razaoSocial(), dados.cnpj(), dados.inscricaoEstadual(), dados.endereco());
        lojas.save(loja);
        auditor.registrar(quem, lojaId, "LOJA_ALTERADA", "Loja", lojaId, "dados fiscais, cnpj " + loja.getCnpj());
        return LojaVista.de(loja);
    }

    private static void conferirCnpj(String cnpj) {
        if (cnpj != null && !Loja.cnpjValido(cnpj)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CNPJ inválido (confira os dígitos verificadores)");
        }
    }

    @PutMapping("/lojas/{lojaId}/estoque/{produtoId}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    @Transactional
    public EstoqueVista ajustarEstoque(@RequestAttribute(AuthController.USUARIO) Usuario quem,
                                       @PathVariable Long lojaId, @PathVariable Long produtoId,
                                       @Valid @RequestBody NovaQuantidade corpo) {
        AuthController.escopo(quem, lojaId); // gerente só mexe na própria loja
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

package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Fichas técnicas: quanto de cada insumo gasta 1 unidade (tamanho Médio) de um produto ou de um extra. Só o ADMIN edita. */
@RestController
@RequestMapping("/fichas")
public class FichaController {

    public record Linha(@NotNull Long insumoId,
                        @NotNull @DecimalMin("0.001") @DecimalMax("100000") @Digits(integer = 6, fraction = 3) BigDecimal quantidade) {}
    public record Ficha(@NotNull @Size(max = 30) List<@NotNull @Valid Linha> itens) {}
    public record ItemFicha(Long insumoId, String nome, UnidadeInsumo unidade, BigDecimal quantidade) {}
    public record FichaVista(List<ItemFicha> itens) {}

    private final FichaProdutoRepository fichasProduto;
    private final FichaExtraRepository fichasExtra;
    private final InsumoRepository insumos;
    private final ProdutoRepository produtos;
    private final Auditor auditor;

    public FichaController(FichaProdutoRepository fichasProduto, FichaExtraRepository fichasExtra, InsumoRepository insumos,
                           ProdutoRepository produtos, Auditor auditor) {
        this.fichasProduto = fichasProduto;
        this.fichasExtra = fichasExtra;
        this.insumos = insumos;
        this.produtos = produtos;
        this.auditor = auditor;
    }

    @GetMapping("/produtos/{id}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public FichaVista produto(@PathVariable Long id) {
        existe(id);
        return vista(fichasProduto.findByIdProdutoId(id).stream().collect(Collectors.toMap(f -> f.getId().insumoId(), FichaProduto::getQuantidade)));
    }

    @PutMapping("/produtos/{id}")
    @Papeis(Papel.ADMIN)
    @Transactional
    public FichaVista salvarProduto(@RequestAttribute(Autenticacao.USUARIO) Usuario quem, @PathVariable Long id, @Valid @RequestBody Ficha ficha) {
        existe(id);
        validar(ficha);
        fichasProduto.apagarDoProduto(id);
        ficha.itens().forEach(l -> fichasProduto.save(new FichaProduto(id, l.insumoId(), l.quantidade())));
        auditor.registrar(quem, null, "FICHA_ALTERADA", "Produto", id, ficha.itens().size() + " insumo(s)");
        return vista(ficha.itens().stream().collect(Collectors.toMap(Linha::insumoId, Linha::quantidade)));
    }

    @GetMapping("/extras/{codigo}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public FichaVista extra(@PathVariable Extra codigo) {
        return vista(fichasExtra.findByIdExtra(codigo).stream().collect(Collectors.toMap(f -> f.getId().insumoId(), FichaExtra::getQuantidade)));
    }

    @PutMapping("/extras/{codigo}")
    @Papeis(Papel.ADMIN)
    @Transactional
    public FichaVista salvarExtra(@RequestAttribute(Autenticacao.USUARIO) Usuario quem, @PathVariable Extra codigo, @Valid @RequestBody Ficha ficha) {
        validar(ficha);
        fichasExtra.apagarDoExtra(codigo);
        ficha.itens().forEach(l -> fichasExtra.save(new FichaExtra(codigo, l.insumoId(), l.quantidade())));
        auditor.registrar(quem, null, "FICHA_ALTERADA", "Extra", codigo, ficha.itens().size() + " insumo(s)");
        return vista(ficha.itens().stream().collect(Collectors.toMap(Linha::insumoId, Linha::quantidade)));
    }

    private void existe(Long produtoId) {
        if (!produtos.existsById(produtoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não existe");
        }
    }

    /** Sem insumo repetido e todos existentes (senão 400). */
    private void validar(Ficha ficha) {
        Set<Long> vistos = new HashSet<>();
        for (Linha l : ficha.itens()) {
            if (!vistos.add(l.insumoId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insumoId " + l.insumoId() + " repetido na ficha");
            }
        }
        Set<Long> existentes = insumos.findAllById(vistos).stream().map(Insumo::getId).collect(Collectors.toSet());
        for (Long id : vistos) {
            if (!existentes.contains(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insumoId " + id + " não existe");
            }
        }
    }

    /** Monta a resposta com o nome e a unidade de cada insumo, na ordem do insumoId. */
    private FichaVista vista(Map<Long, BigDecimal> quantidades) {
        Map<Long, Insumo> porId = insumos.findAllById(quantidades.keySet()).stream().collect(Collectors.toMap(Insumo::getId, Function.identity()));
        return new FichaVista(quantidades.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ItemFicha(e.getKey(), porId.get(e.getKey()).getNome(), porId.get(e.getKey()).getUnidade(), e.getValue()))
                .toList());
    }
}

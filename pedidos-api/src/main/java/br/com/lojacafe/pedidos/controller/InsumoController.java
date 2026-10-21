package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.AuthController.Papeis;
import br.com.lojacafe.pedidos.model.EstoqueInsumo;
import br.com.lojacafe.pedidos.model.Insumo;
import br.com.lojacafe.pedidos.model.Loja;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.UnidadeInsumo;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.AuditoriaRepository;
import br.com.lojacafe.pedidos.repository.EstoqueInsumoRepository;
import br.com.lojacafe.pedidos.repository.InsumoRepository;
import br.com.lojacafe.pedidos.repository.LojaRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Catálogo de insumos da rede, estoque por loja e alertas (specs/api.md, "Insumos e ficha técnica"). */
@RestController
public class InsumoController {

    public record NovoInsumo(@NotBlank @Size(max = 60) String nome, @NotNull UnidadeInsumo unidade) {}
    public record AlterarInsumo(@NotBlank @Size(max = 60) String nome, Boolean ativo) {}
    public record Contagem(@NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 3) BigDecimal quantidade,
                           @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 3) BigDecimal minimo) {}
    public record InsumoVista(Long id, String nome, UnidadeInsumo unidade, boolean ativo, BigDecimal quantidade, BigDecimal minimo,
                              boolean abaixoDoMinimo) {
        static InsumoVista de(EstoqueInsumoRepository.InsumoComEstoque i) {
            return new InsumoVista(i.id(), i.nome(), i.unidade(), i.ativo(), i.quantidade(), i.minimo(), i.quantidade().compareTo(i.minimo()) <= 0);
        }
    }
    /** Os dados do insumo na rede (sem estoque, que é de cada loja). */
    public record InsumoBasico(Long id, String nome, UnidadeInsumo unidade, boolean ativo) {}
    public record Alerta(Long insumoId, String nome, UnidadeInsumo unidade, BigDecimal quantidade, BigDecimal minimo, String situacao) {}
    public record EstoqueInsumoVista(Long lojaId, Long insumoId, BigDecimal quantidade, BigDecimal minimo) {}

    private final InsumoRepository insumos;
    private final EstoqueInsumoRepository estoque;
    private final LojaRepository lojas;
    private final AuditoriaRepository auditor;

    public InsumoController(InsumoRepository insumos, EstoqueInsumoRepository estoque, LojaRepository lojas, AuditoriaRepository auditor) {
        this.insumos = insumos;
        this.estoque = estoque;
        this.lojas = lojas;
        this.auditor = auditor;
    }

    @GetMapping("/insumos")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<InsumoVista> listar(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @RequestParam(required = false) Long lojaId) {
        Long loja = CaixaController.lojaObrigatoria(usuario, lojaId);
        return estoque.catalogo(loja).stream().map(InsumoVista::de).toList();
    }

    @PostMapping("/insumos")
    @Papeis(Papel.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public InsumoBasico criar(@RequestAttribute(AuthController.USUARIO) Usuario quem, @Valid @RequestBody NovoInsumo novo) {
        if (insumos.findByNomeIgnoreCase(novo.nome().trim()).isPresent()) {
            throw AuthController.nomeRepetido("insumo");
        }
        Insumo insumo = insumos.save(new Insumo(novo.nome(), novo.unidade()));
        // Nasce sem estoque (e sem mínimo) em todas as lojas; cada gerente faz a contagem da sua.
        for (Loja loja : lojas.findAll()) {
            estoque.save(new EstoqueInsumo(loja.getId(), insumo.getId(), BigDecimal.ZERO, BigDecimal.ZERO));
        }
        auditor.registrar(quem, null, "INSUMO_CRIADO", "Insumo", insumo.getId(), insumo.getNome() + " (" + insumo.getUnidade() + ")");
        return new InsumoBasico(insumo.getId(), insumo.getNome(), insumo.getUnidade(), true);
    }

    @PutMapping("/insumos/{id}")
    @Papeis(Papel.ADMIN)
    @Transactional
    public InsumoBasico alterar(@RequestAttribute(AuthController.USUARIO) Usuario quem, @PathVariable Long id, @Valid @RequestBody AlterarInsumo dados) {
        Insumo insumo = insumos.findById(id).orElseThrow(() -> AuthController.naoExiste("Insumo"));
        insumos.findByNomeIgnoreCase(dados.nome().trim()).filter(outro -> !outro.getId().equals(id)).ifPresent(outro -> {
            throw AuthController.nomeRepetido("insumo");
        });
        List<String> mudancas = new ArrayList<>();
        if (!insumo.getNome().equals(dados.nome().trim())) {
            mudancas.add("nome " + insumo.getNome() + " -> " + dados.nome().trim());
        }
        boolean ativo = dados.ativo() == null ? insumo.isAtivo() : dados.ativo();
        if (ativo != insumo.isAtivo()) {
            mudancas.add("ativo " + insumo.isAtivo() + " -> " + ativo);
        }
        insumo.setNome(dados.nome());
        insumo.setAtivo(ativo);
        insumos.save(insumo);
        if (!mudancas.isEmpty()) {
            auditor.registrar(quem, null, "INSUMO_ALTERADO", "Insumo", id, String.join("; ", mudancas));
        }
        return new InsumoBasico(id, insumo.getNome(), insumo.getUnidade(), ativo);
    }

    /** A contagem do estoque de um insumo numa loja (e, se vier, o mínimo do alerta). O gerente só mexe na própria loja. */
    @PutMapping("/lojas/{lojaId}/insumos/{insumoId}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    @Transactional
    public EstoqueInsumoVista contar(@RequestAttribute(AuthController.USUARIO) Usuario quem, @PathVariable Long lojaId,
                                     @PathVariable Long insumoId, @Valid @RequestBody Contagem corpo) {
        AuthController.escopo(quem, lojaId);
        if (!lojas.existsById(lojaId)) {
            throw AuthController.naoExiste("Loja");
        }
        Insumo insumo = insumos.findById(insumoId).orElseThrow(() -> AuthController.naoExiste("Insumo"));
        EstoqueInsumo linha = estoque.findById(new EstoqueInsumo.Id(lojaId, insumoId))
                .orElseGet(() -> new EstoqueInsumo(lojaId, insumoId, BigDecimal.ZERO, BigDecimal.ZERO));
        String detalhe = insumo.getNome() + ": " + linha.getQuantidade() + " -> " + corpo.quantidade();
        linha.setQuantidade(corpo.quantidade());
        if (corpo.minimo() != null) {
            detalhe += "; mínimo " + linha.getMinimo() + " -> " + corpo.minimo();
            linha.setMinimo(corpo.minimo());
        }
        estoque.save(linha);
        auditor.registrar(quem, lojaId, "INSUMO_AJUSTADO", "Insumo", insumoId, detalhe);
        return new EstoqueInsumoVista(lojaId, insumoId, linha.getQuantidade(), linha.getMinimo());
    }

    /** Insumos ativos com quantidade ≤ mínimo; os que acabaram (≤ 0) primeiro. */
    @GetMapping("/insumos/alertas")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<Alerta> alertas(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @RequestParam(required = false) Long lojaId) {
        Long loja = CaixaController.lojaObrigatoria(usuario, lojaId);
        return estoque.catalogo(loja).stream()
                .filter(i -> i.ativo() && i.quantidade().compareTo(i.minimo()) <= 0)
                .map(i -> new Alerta(i.id(), i.nome(), i.unidade(), i.quantidade(), i.minimo(),
                        i.quantidade().signum() <= 0 ? "ACABOU" : "BAIXO"))
                .sorted(Comparator.comparing((Alerta a) -> !a.situacao().equals("ACABOU")).thenComparing(Alerta::nome))
                .toList();
    }
}

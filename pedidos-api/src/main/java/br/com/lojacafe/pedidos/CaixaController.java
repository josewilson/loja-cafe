package br.com.lojacafe.pedidos;

import br.com.lojacafe.pedidos.AuthController.Papeis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Caixa da loja por turno (specs/api.md): resumo do turno aberto, fechamento e histórico. */
@RestController
@RequestMapping("/caixa")
public class CaixaController {

    public record FormaTotal(FormaPagamento forma, long pedidos, BigDecimal total) {}
    public record ResumoCaixa(Long lojaId, Instant desde, long pedidos, BigDecimal total, List<FormaTotal> porForma, long aguardando) {}
    public record Fechar(@NotNull @DecimalMin("0.00") @DecimalMax("10000000.00") @Digits(integer = 8, fraction = 2) BigDecimal dinheiroContado,
                         @Size(max = 140) String observacao) {}
    public record FechamentoVista(Long id, Long lojaId, Long usuarioId, Instant desde, Instant ate, int pedidos, BigDecimal total,
                                  List<FormaTotal> porForma, BigDecimal dinheiroEsperado, BigDecimal dinheiroContado,
                                  BigDecimal diferenca, String observacao, Instant criadoEm) {}

    private final PedidoRepository pedidos;
    private final FechamentoCaixaRepository fechamentos;
    private final AuditoriaRepository auditor;

    public CaixaController(PedidoRepository pedidos, FechamentoCaixaRepository fechamentos, AuditoriaRepository auditor) {
        this.pedidos = pedidos;
        this.fechamentos = fechamentos;
        this.auditor = auditor;
    }

    /** Transforma as linhas {forma, quantidade, soma} de uma consulta em totais por forma, do maior para o menor. */
    static List<FormaTotal> porForma(List<Object[]> linhas) {
        return linhas.stream()
                .map(l -> new FormaTotal((FormaPagamento) l[0], (Long) l[1], (BigDecimal) l[2]))
                .sorted(Comparator.comparing(FormaTotal::total).reversed())
                .toList();
    }

    static BigDecimal soma(List<FormaTotal> formas) {
        return formas.stream().map(FormaTotal::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static long quantidade(List<FormaTotal> formas) {
        return formas.stream().mapToLong(FormaTotal::pedidos).sum();
    }

    /** O turno aberto: o que foi vendido desde o último fechamento. */
    @GetMapping("/resumo")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public ResumoCaixa resumo(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @RequestParam(required = false) Long lojaId) {
        Long loja = lojaObrigatoria(usuario, lojaId);
        var formas = porForma(pedidos.turnoAbertoPorForma(loja, StatusPedido.PAGO, Preparo.CANCELADO));
        Instant desde = fechamentos.findFirstByLojaIdOrderByIdDesc(loja).map(FechamentoCaixa::getAte).orElse(null);
        return new ResumoCaixa(loja, desde, quantidade(formas), soma(formas), formas,
                pedidos.countByLojaIdAndStatus(loja, StatusPedido.AGUARDANDO_PAGAMENTO));
    }

    /**
     * Fecha o turno. Cada pedido vendido é ligado a este fechamento (e só a ele) por uma única instrução; os totais saem
     * dos pedidos ligados. Quem aprova o pagamento só depois do fechamento entra no próximo, sem se perder.
     */
    @PostMapping("/fechamentos")
    @Papeis(Papel.GERENTE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public FechamentoVista fechar(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @Valid @RequestBody Fechar corpo) {
        Long loja = usuario.getLojaId();
        Instant desde = fechamentos.findFirstByLojaIdOrderByIdDesc(loja).map(FechamentoCaixa::getAte).orElse(null);
        FechamentoCaixa novo = fechamentos.save(new FechamentoCaixa(loja, usuario.getId(), desde, corpo.dinheiroContado(), corpo.observacao()));
        int ligados = pedidos.fechar(loja, novo.getId(), StatusPedido.PAGO, Preparo.CANCELADO);

        var formas = porForma(pedidos.doFechamentoPorForma(novo.getId()));
        BigDecimal esperado = formas.stream().filter(f -> f.forma() == FormaPagamento.DINHEIRO)
                .map(FormaTotal::total).findFirst().orElse(BigDecimal.ZERO);
        FechamentoCaixa fechamento = fechamentos.findById(novo.getId()).orElseThrow();
        fechamento.completar(ligados, soma(formas), esperado);
        fechamentos.save(fechamento);

        auditor.registrar(usuario, loja, "CAIXA_FECHADO", "FechamentoCaixa", fechamento.getId(),
                "total " + fechamento.getTotal() + ", dinheiro esperado " + esperado + ", contado " + corpo.dinheiroContado()
                        + ", diferença " + fechamento.getDiferenca());
        return vista(fechamento, formas);
    }

    /** Histórico, do mais novo ao mais antigo (padrão 50, de 1 a 200). */
    @GetMapping("/fechamentos")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<FechamentoVista> historico(@RequestAttribute(AuthController.USUARIO) Usuario usuario,
                                           @RequestParam(defaultValue = "50") int limite,
                                           @RequestParam(required = false) Long lojaId) {
        var pagina = PageRequest.of(0, Math.max(1, Math.min(limite, 200)));
        Long loja = AuthController.escopo(usuario, lojaId);
        var lista = loja == null ? fechamentos.findAllByOrderByIdDesc(pagina) : fechamentos.findByLojaIdOrderByIdDesc(loja, pagina);
        // ponytail: uma consulta por fechamento para a quebra por forma (no máximo 200); se o histórico crescer, guardar a quebra no fechamento.
        return lista.stream().map(f -> vista(f, porForma(pedidos.doFechamentoPorForma(f.getId())))).toList();
    }

    private static FechamentoVista vista(FechamentoCaixa f, List<FormaTotal> formas) {
        return new FechamentoVista(f.getId(), f.getLojaId(), f.getUsuarioId(), f.getDesde(), f.getAte(), f.getPedidos(), f.getTotal(),
                formas, f.getDinheiroEsperado(), f.getDinheiroContado(), f.getDiferenca(), f.getObservacao(), f.getCriadoEm());
    }

    static Long lojaObrigatoria(Usuario usuario, Long lojaId) {
        Long loja = AuthController.escopo(usuario, lojaId);
        if (loja == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe lojaId");
        }
        return loja;
    }
}

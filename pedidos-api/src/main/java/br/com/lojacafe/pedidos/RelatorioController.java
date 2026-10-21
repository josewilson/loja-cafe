package br.com.lojacafe.pedidos;

import br.com.lojacafe.pedidos.AuthController.Papeis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Vendas de um dia da loja (specs/api.md). O dia vai de 00:00 a 24:00 no fuso de São Paulo, pela hora de criação do pedido. */
@RestController
@RequestMapping("/relatorios")
public class RelatorioController {

    public record ItemVendido(String item, long quantidade, BigDecimal total) {}
    public record Vendas(LocalDate data, Long lojaId, long pedidos, BigDecimal total, BigDecimal ticketMedio,
                         List<CaixaController.FormaTotal> porForma, List<ItemVendido> maisVendidos,
                         long recusados, long cancelados, long aguardando, long estornados, long estornosPendentes) {}

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final int TOP = 5;

    private final PedidoRepository pedidos;

    public RelatorioController(PedidoRepository pedidos) {
        this.pedidos = pedidos;
    }

    @GetMapping("/vendas")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public Vendas vendas(@RequestAttribute(AuthController.USUARIO) Usuario usuario,
                         @RequestParam(required = false) String data, @RequestParam(required = false) Long lojaId) {
        Long loja = CaixaController.lojaObrigatoria(usuario, lojaId);
        LocalDate dia;
        try {
            dia = data == null ? LocalDate.now(FUSO) : LocalDate.parse(data);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data deve ser AAAA-MM-DD");
        }
        Instant de = dia.atStartOfDay(FUSO).toInstant();
        Instant ate = dia.plusDays(1).atStartOfDay(FUSO).toInstant();

        var formas = CaixaController.porForma(pedidos.vendasPorForma(loja, de, ate, StatusPedido.PAGO, Preparo.CANCELADO));
        long vendidos = CaixaController.quantidade(formas);
        BigDecimal total = CaixaController.soma(formas);
        BigDecimal ticket = vendidos == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(vendidos), 2, RoundingMode.HALF_UP);
        var top = pedidos.maisVendidos(loja, de, ate, StatusPedido.PAGO, Preparo.CANCELADO, PageRequest.of(0, TOP)).stream()
                .map(l -> new ItemVendido((String) l[0], (Long) l[1], (BigDecimal) l[2])).toList();

        return new Vendas(dia, loja, vendidos, total, ticket, formas, top,
                pedidos.countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndStatus(loja, de, ate, StatusPedido.RECUSADO),
                pedidos.countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndPreparo(loja, de, ate, Preparo.CANCELADO),
                pedidos.countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndStatus(loja, de, ate, StatusPedido.AGUARDANDO_PAGAMENTO),
                pedidos.countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndEstornadoEmIsNotNull(loja, de, ate),
                pedidos.countByLojaIdAndStatusAndPreparoAndEstornadoEmIsNull(loja, StatusPedido.PAGO, Preparo.CANCELADO));
    }
}

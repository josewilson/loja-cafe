package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Pedido;
import br.com.lojacafe.pedidos.model.Preparo;
import br.com.lojacafe.pedidos.model.StatusPedido;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.PedidoRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/** Vendas de um dia ou de um período da loja (specs/api.md). O dia vai de 00:00 a 24:00 no fuso de São Paulo, pela hora de criação do pedido. */
@Service
public class RelatorioService {

    public record ItemVendido(String item, long quantidade, BigDecimal total) {}
    public record Vendas(LocalDate data, LocalDate ate, Long lojaId, long pedidos, BigDecimal total, BigDecimal ticketMedio,
                         List<CaixaService.FormaTotal> porForma, List<ItemVendido> maisVendidos,
                         long recusados, long cancelados, long aguardando, long estornados, long estornosPendentes) {}

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final int TOP = 5;
    private static final int MAX_DIAS = 366;

    private final PedidoRepository pedidos;

    public RelatorioService(PedidoRepository pedidos) {
        this.pedidos = pedidos;
    }
    public Vendas vendas(Usuario usuario, String data,
                         String ate, Long lojaId) {
        Long loja = CaixaService.lojaObrigatoria(usuario, lojaId);
        LocalDate dia = dia(data, "data", LocalDate.now(FUSO));
        LocalDate fim = periodo(dia, ate);
        Instant de = dia.atStartOfDay(FUSO).toInstant();
        Instant ateInstante = fim.plusDays(1).atStartOfDay(FUSO).toInstant();
        return resumo(loja, dia, fim, de, ateInstante);
    }

    /** Uma linha por venda do período, para o contador (Excel brasileiro: separador ";" e vírgula decimal). */
    public String vendasCsv(Usuario usuario, String data,
                            String ate, Long lojaId) {
        Long loja = CaixaService.lojaObrigatoria(usuario, lojaId);
        LocalDate dia = dia(data, "data", LocalDate.now(FUSO));
        LocalDate fim = periodo(dia, ate);
        var linhas = pedidos.findByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndStatusAndPreparoNotOrderByIdAsc(loja,
                dia.atStartOfDay(FUSO).toInstant(), fim.plusDays(1).atStartOfDay(FUSO).toInstant(), StatusPedido.PAGO, Preparo.CANCELADO);
        var csv = new StringBuilder("pedido;data;hora;forma;valor\n");
        for (Pedido p : linhas) {
            var quando = p.getCriadoEm().atZone(FUSO);
            csv.append(p.getId()).append(';').append(quando.toLocalDate()).append(';').append(quando.toLocalTime().withNano(0)).append(';')
                    .append(p.getFormaPagamento()).append(';').append(p.getValor().toPlainString().replace('.', ',')).append('\n');
        }
        return csv.toString();
    }

    private static LocalDate dia(String texto, String campo, LocalDate padrao) {
        try {
            return texto == null ? padrao : LocalDate.parse(texto);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, campo + " deve ser AAAA-MM-DD");
        }
    }

    /** Último dia do período (inclusive): {@code ate} ou o próprio dia; no máximo um ano. */
    private static LocalDate periodo(LocalDate dia, String ate) {
        LocalDate fim = dia(ate, "ate", dia);
        if (fim.isBefore(dia) || fim.isAfter(dia.plusDays(MAX_DIAS - 1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ate deve ficar entre data e " + MAX_DIAS + " dias depois");
        }
        return fim;
    }

    private Vendas resumo(Long loja, LocalDate dia, LocalDate fim, Instant de, Instant ate) {
        var formas = CaixaService.porForma(pedidos.vendasPorForma(loja, de, ate, StatusPedido.PAGO, Preparo.CANCELADO));
        long vendidos = CaixaService.quantidade(formas);
        BigDecimal total = CaixaService.soma(formas);
        BigDecimal ticket = vendidos == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(vendidos), 2, RoundingMode.HALF_UP);
        var top = pedidos.maisVendidos(loja, de, ate, StatusPedido.PAGO, Preparo.CANCELADO, PageRequest.of(0, TOP)).stream()
                .map(l -> new ItemVendido((String) l[0], (Long) l[1], (BigDecimal) l[2])).toList();

        return new Vendas(dia, fim, loja, vendidos, total, ticket, formas, top,
                pedidos.countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndStatus(loja, de, ate, StatusPedido.RECUSADO),
                pedidos.countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndPreparo(loja, de, ate, Preparo.CANCELADO),
                pedidos.countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndStatus(loja, de, ate, StatusPedido.AGUARDANDO_PAGAMENTO),
                pedidos.countByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanAndEstornadoEmIsNotNull(loja, de, ate),
                pedidos.countByLojaIdAndStatusAndPreparoAndEstornadoEmIsNull(loja, StatusPedido.PAGO, Preparo.CANCELADO));
    }
}

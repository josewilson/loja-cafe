package br.com.lojacafe.pedidos;

import br.com.lojacafe.pedidos.AuthController.Papeis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    /** Corpo do POST /pedidos (specs/api.md). O preço NÃO vem do cliente: a API calcula. */
    public record NovoPedido(
            @Size(max = 60) String cliente,
            @NotNull FormaPagamento formaPagamento,
            @Min(1) @Max(3) Integer parcelas,
            @Size(max = 140) String observacao,
            @NotEmpty @Size(max = 10) List<@NotNull @Valid Item> itens) {

        public record Item(
                @NotNull Long produtoId,
                @Min(1) @Max(50) int quantidade,
                Tamanho tamanho,
                @Size(max = 5) List<Extra> extras) {
        }
    }

    private final PedidoRepository repository;
    private final ProdutoRepository produtos;
    private final EstoqueLojaRepository estoque;
    private final PagamentosKafka eventos;
    private final AuditoriaRepository auditor;
    private final FichaController consumo;
    private final LojaRepository lojas;
    private final Nfce nfce;

    public PedidoController(PedidoRepository repository, ProdutoRepository produtos, EstoqueLojaRepository estoque,
                            PagamentosKafka eventos, AuditoriaRepository auditor, FichaController consumo, LojaRepository lojas, Nfce nfce) {
        this.nfce = nfce;
        this.lojas = lojas;
        this.consumo = consumo;
        this.repository = repository;
        this.produtos = produtos;
        this.estoque = estoque;
        this.eventos = eventos;
        this.auditor = auditor;
    }

    // Tudo numa transação: se faltar estoque ou o Kafka falhar, o estoque baixado e o pedido gravado voltam atrás.
    @PostMapping
    @Papeis({Papel.ATENDENTE, Papel.GERENTE})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(rollbackFor = Exception.class)
    public Pedido criar(@RequestAttribute(AuthController.USUARIO) Usuario usuario,
                        @Valid @RequestBody NovoPedido novo) throws Exception {
        FormaPagamento forma = novo.formaPagamento();
        if (novo.parcelas() != null && novo.parcelas() > forma.getParcelasMax()) {
            throw erro(forma.getNome() + " aceita no máximo " + forma.getParcelasMax() + " parcela(s)");
        }

        List<ItemPedido> itens = new ArrayList<>();
        for (NovoPedido.Item i : novo.itens()) {
            // O preço vem do cardápio da rede; produto desconhecido é erro do cliente (400).
            Produto produto = produtos.findById(i.produtoId()).orElseThrow(
                    () -> erro("produtoId " + i.produtoId() + " não existe"));
            if (!produto.isAtivo()) {
                throw erro("Produto " + produto.getNome() + " não está mais no cardápio");
            }
            // O estoque que baixa é o da loja de quem está lançando.
            if (estoque.baixar(usuario.getLojaId(), produto.getId(), i.quantidade()) == 0) {
                throw erro("Estoque insuficiente de " + produto.getNome());
            }
            itens.add(new ItemPedido(produto, i.quantidade(), i.tamanho(), i.extras()));
        }
        Pedido pedido = repository.save(new Pedido(usuario, novo.cliente(), forma, novo.parcelas(), novo.observacao(), itens));
        consumo.registrarEBaixar(pedido); // insumos da ficha técnica; nunca bloqueia a venda
        auditor.registrar(usuario, pedido.getLojaId(), "PEDIDO_CRIADO", "Pedido", pedido.getId(), "valor " + pedido.getValor());

        // Ato 3: anota o fato no Kafka e responde na hora; o Pagamentos lê o tópico no seu ritmo.
        eventos.publicarCriado(pedido);
        return pedido;
    }

    /** Da loja do usuário, mais novos primeiro. `limite` (padrão 100, de 1 a 200). O ADMIN vê todas (ou filtra por lojaId). */
    @GetMapping
    public List<Pedido> listar(@RequestAttribute(AuthController.USUARIO) Usuario usuario,
                               @RequestParam(defaultValue = "100") int limite,
                               @RequestParam(required = false) Long lojaId) {
        var pagina = PageRequest.of(0, Math.max(1, Math.min(limite, 200)));
        Long loja = AuthController.escopo(usuario, lojaId);
        return loja == null ? repository.findAllByOrderByIdDesc(pagina) : repository.findByLojaIdOrderByIdDesc(loja, pagina);
    }

    public record LojaRecibo(String nome, String razaoSocial, String cnpj, String inscricaoEstadual, String endereco) {}
    public record Recibo(Long pedidoId, LojaRecibo loja, String cliente, String atendente, List<ItemPedido> itens, BigDecimal valor,
                         FormaPagamento formaPagamento, Integer parcelas, Instant criadoEm, String aviso) {}

    /** Recibo para imprimir (sem valor fiscal). Só de pedido PAGO e não cancelado, e só da loja de quem pede (o ADMIN vê qualquer uma). */
    @GetMapping("/{id}/recibo")
    @Transactional
    public Recibo recibo(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @PathVariable Long id) {
        Pedido p = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não existe"));
        AuthController.escopo(usuario, p.getLojaId());
        if (p.getStatus() != StatusPedido.PAGO || p.getPreparo() == Preparo.CANCELADO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só há recibo de pedido pago e não cancelado");
        }
        Loja l = lojas.findById(p.getLojaId()).orElseThrow();
        auditor.registrar(usuario, p.getLojaId(), "RECIBO_EMITIDO", "Pedido", id, null);
        return new Recibo(p.getId(), new LojaRecibo(l.getNome(), l.getRazaoSocial(), l.getCnpj(), l.getInscricaoEstadual(), l.getEndereco()),
                p.getCliente(), p.getAtendente(), p.getItens(), p.getValor(), p.getFormaPagamento(), p.getParcelas(), p.getCriadoEm(),
                "Sem valor fiscal");
    }

    /**
     * Emite a NFC-e de um pedido pago e não cancelado (Fase 6b). Repetir é seguro: nota já autorizada volta como está; rejeitada tenta de novo.
     * Exige CNPJ da loja e NCM/CFOP de cada produto (quem define é o contador). Provedor desligado: 409.
     */
    @PostMapping("/{id}/nfce")
    @Papeis({Papel.ATENDENTE, Papel.GERENTE})
    @Transactional
    public Pedido emitirNfce(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @PathVariable Long id) {
        if (!nfce.ligada()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A emissão de NFC-e está desligada (NFCE_PROVEDOR)");
        }
        Pedido p = repository.findWithLockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não existe"));
        AuthController.escopo(usuario, p.getLojaId());
        if (p.getStatus() != StatusPedido.PAGO || p.getPreparo() == Preparo.CANCELADO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só se emite NFC-e de pedido pago e não cancelado");
        }
        if ("AUTORIZADA".equals(p.getNfceStatus())) {
            return p;
        }
        Loja loja = lojas.findById(p.getLojaId()).orElseThrow();
        if (loja.getCnpj() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A loja ainda não tem CNPJ (dados fiscais)");
        }
        var catalogo = new java.util.HashMap<Long, Produto>();
        produtos.findAllById(p.getItens().stream().map(ItemPedido::getProdutoId).toList()).forEach(x -> catalogo.put(x.getId(), x));
        for (ItemPedido i : p.getItens()) {
            Produto produto = catalogo.get(i.getProdutoId());
            if (produto == null || produto.getNcm() == null || produto.getCfop() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, i.getItem() + " está sem NCM ou CFOP no cardápio");
            }
        }
        Nfce.Resultado resultado = nfce.emitir(p, loja, catalogo);
        p.registrarNfce(resultado);
        auditor.registrar(usuario, p.getLojaId(), resultado.status().equals("AUTORIZADA") ? "NFCE_EMITIDA" : "NFCE_REJEITADA", "Pedido", id,
                resultado.status().equals("AUTORIZADA") ? "nota " + resultado.numero() : resultado.mensagem());
        return repository.save(p);
    }

    private static ResponseStatusException erro(String mensagem) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagem);
    }
}

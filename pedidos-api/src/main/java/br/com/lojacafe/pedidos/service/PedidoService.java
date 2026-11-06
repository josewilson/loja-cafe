package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.Extra;
import br.com.lojacafe.pedidos.model.FormaPagamento;
import br.com.lojacafe.pedidos.model.ItemPedido;
import br.com.lojacafe.pedidos.model.Loja;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Pedido;
import br.com.lojacafe.pedidos.model.Preparo;
import br.com.lojacafe.pedidos.model.Produto;
import br.com.lojacafe.pedidos.model.StatusPedido;
import br.com.lojacafe.pedidos.model.Tamanho;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.AuditoriaRepository;
import br.com.lojacafe.pedidos.repository.EstoqueLojaRepository;
import br.com.lojacafe.pedidos.repository.LojaRepository;
import br.com.lojacafe.pedidos.repository.PedidoRepository;
import br.com.lojacafe.pedidos.repository.ProdutoRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * As regras do pedido, do balcão à cozinha: criar, listar, recibo, NFC-e, andamento do preparo, confirmação do cartão,
 * cancelamento e estorno. Os controllers só recebem a requisição e chamam aqui. Cada método é uma transação; erro de regra
 * sai como ResponseStatusException (o Spring a transforma na resposta HTTP).
 */
@Service
public class PedidoService {

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

    public record LojaRecibo(String nome, String razaoSocial, String cnpj, String inscricaoEstadual, String endereco) {}
    public record Recibo(Long pedidoId, LojaRecibo loja, String cliente, String atendente, List<ItemPedido> itens, BigDecimal valor,
                         FormaPagamento formaPagamento, Integer parcelas, Instant criadoEm, String aviso) {}

    private final PedidoRepository repository;
    private final ProdutoRepository produtos;
    private final EstoqueLojaRepository estoque;
    private final PagamentosKafka eventos;
    private final AuditoriaRepository auditor;
    private final ConsumoInsumos consumo;
    private final LojaRepository lojas;
    private final Nfce nfce;

    public PedidoService(PedidoRepository repository, ProdutoRepository produtos, EstoqueLojaRepository estoque,
                         PagamentosKafka eventos, AuditoriaRepository auditor, ConsumoInsumos consumo, LojaRepository lojas, Nfce nfce) {
        this.repository = repository;
        this.produtos = produtos;
        this.estoque = estoque;
        this.eventos = eventos;
        this.auditor = auditor;
        this.consumo = consumo;
        this.lojas = lojas;
        this.nfce = nfce;
    }

    // ---------- balcão ----------

    // Tudo numa transação: se faltar estoque, o estoque baixado e o pedido gravado voltam atrás. O evento vai para a Outbox
    // na mesma transação: ou o pedido e o evento existem os dois, ou nenhum dos dois.
    @Transactional
    public Pedido criar(Usuario usuario, NovoPedido novo) {
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
        eventos.publicarCriado(pedido);
        return pedido;
    }

    /** Da loja do usuário, mais novos primeiro. O ADMIN vê todas (ou filtra por lojaId). */
    public List<Pedido> listar(Usuario usuario, int limite, Long lojaId) {
        var pagina = Acesso.pagina(limite, 200);
        Long loja = Acesso.escopo(usuario, lojaId);
        return loja == null ? repository.findAllByOrderByIdDesc(pagina) : repository.findByLojaIdOrderByIdDesc(loja, pagina);
    }

    /** Recibo para imprimir (sem valor fiscal). Só de pedido PAGO e não cancelado, e só da loja de quem pede (o ADMIN vê qualquer uma). */
    @Transactional
    public Recibo recibo(Usuario usuario, Long id) {
        Pedido p = repository.findById(id).orElseThrow(() -> Acesso.naoExiste("Pedido"));
        Acesso.escopo(usuario, p.getLojaId());
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
    @Transactional
    public Pedido emitirNfce(Usuario usuario, Long id) {
        if (!nfce.ligada()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A emissão de NFC-e está desligada (NFCE_PROVEDOR)");
        }
        Pedido p = repository.findWithLockById(id).orElseThrow(() -> Acesso.naoExiste("Pedido"));
        Acesso.escopo(usuario, p.getLojaId());
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
        var catalogo = new HashMap<Long, Produto>();
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

    // ---------- cozinha, pagamento no balcão, cancelamento e estorno ----------

    /** A fila da loja: só pedidos PAGO ainda não retirados nem cancelados, do mais antigo ao mais novo. */
    public List<Pedido> fila(Usuario usuario, int limite, Long lojaId) {
        Long loja = Acesso.escopo(usuario, lojaId);
        if (loja == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe lojaId");
        }
        return repository.findByLojaIdAndStatusAndPreparoInOrderByIdAsc(loja, StatusPedido.PAGO,
                Set.of(Preparo.NA_FILA, Preparo.PREPARANDO, Preparo.PRONTO), Acesso.pagina(limite, 200));
    }

    @Transactional
    public Pedido mudarPreparo(Usuario usuario, Long id, Preparo novo) {
        // Quem pode dar cada passo: a cozinha prepara; o balcão entrega; o gerente faz os dois.
        Set<Papel> permitidos = switch (novo) {
            case PREPARANDO, PRONTO -> Set.of(Papel.COZINHA, Papel.GERENTE);
            case RETIRADO -> Set.of(Papel.ATENDENTE, Papel.GERENTE);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use PREPARANDO, PRONTO ou RETIRADO (cancelar é em /cancelar)");
        };
        if (!permitidos.contains(usuario.getPapel())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu papel não dá este passo");
        }
        Pedido pedido = carregar(usuario, id);
        if (pedido.getStatus() != StatusPedido.PAGO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O pedido ainda não foi pago");
        }
        Preparo antes = pedido.getPreparo();
        if (!pedido.avancarPara(novo)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O pedido está " + antes + "; só avança de " + novo.anterior() + " para " + novo);
        }
        auditor.registrar(usuario, pedido.getLojaId(), "PREPARO_ALTERADO", "Pedido", id, antes + " -> " + novo);
        return repository.save(pedido);
    }

    /**
     * Cartão passado na maquininha: o atendente diz se foi aprovado (com o NSU do comprovante, se quiser) ou recusado.
     * Só vale em pedido CREDITO ou DEBITO que ainda aguarda o pagamento. Recusado devolve estoque e insumos, como no Pagamentos.
     */
    @Transactional
    public Pedido confirmarPagamento(Usuario usuario, Long id, boolean aprovado, String nsu) {
        Pedido pedido = carregar(usuario, id);
        if (pedido.getFormaPagamento() != FormaPagamento.CREDITO && pedido.getFormaPagamento() != FormaPagamento.DEBITO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só pagamento em cartão (crédito ou débito) é confirmado à mão");
        }
        String motivo = aprovado ? null : "recusado na maquininha";
        if (!eventos.concluir(pedido, aprovado, motivo, "maquininha")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O pagamento deste pedido já foi decidido");
        }
        pedido.registrarNsu(aprovado ? nsu : null);
        auditor.registrar(usuario, pedido.getLojaId(), aprovado ? "PAGAMENTO_CONFIRMADO" : "PAGAMENTO_RECUSADO", "Pedido", id,
                pedido.getNsu() == null ? null : "nsu " + pedido.getNsu());
        return repository.save(pedido);
    }

    @Transactional
    public Pedido cancelar(Usuario usuario, Long id, String justificativa) {
        Pedido pedido = carregar(usuario, id);
        Preparo antes = pedido.getPreparo();
        if (!pedido.cancelar()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este pedido não pode mais ser cancelado");
        }
        String motivo = justificativa == null ? "" : justificativa.trim();
        if ("AUTORIZADA".equals(pedido.getNfceStatus())) {
            // Nota fiscal emitida: só se cancela o pedido junto com a nota, e a SEFAZ só aceita nos primeiros 30 minutos.
            if (!pedido.nfceCancelavel()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A NFC-e deste pedido passou de 30 minutos: cancele a nota com o contador antes");
            }
            nfce.cancelar(pedido, motivo.length() >= 15 ? motivo : "Cancelamento da venda a pedido do cliente");
            pedido.cancelarNfce();
            auditor.registrar(usuario, pedido.getLojaId(), "NFCE_CANCELADA", "Pedido", id, null);
        }
        // A trava da linha garante que só um cancelamento chega aqui: o estoque volta uma única vez.
        pedido.getItens().forEach(i -> estoque.repor(pedido.getLojaId(), i.getProdutoId(), i.getQuantidade()));
        // Insumos: só voltam se ainda estava na fila. Se a cozinha já começou, foram usados de verdade (perda).
        if (antes == Preparo.NA_FILA) {
            consumo.devolver(pedido);
        }
        auditor.registrar(usuario, pedido.getLojaId(), "PEDIDO_CANCELADO", "Pedido", id, motivo.isEmpty() ? null : motivo);
        if (pedido.getStatus() == StatusPedido.PAGO && pedido.getFormaPagamento() == FormaPagamento.PIX) {
            // Pix pago: pede a devolução ao provedor (pela Outbox, na mesma transação do cancelamento). O resultado volta por
            // pagamentos-resultados; se o provedor falhar, o gerente registra à mão. A linha segue travada até o fim desta transação,
            // então a resposta do Pagamentos espera o cancelamento gravar.
            eventos.publicarEstorno(pedido);
        }
        return repository.save(pedido);
    }

    /** Registra que o dinheiro do pedido pago e cancelado foi devolvido (na maquininha ou no app do Pix). O sistema não move dinheiro. */
    @Transactional
    public Pedido estornar(Usuario usuario, Long id, String justificativa) {
        Pedido pedido = carregar(usuario, id);
        if (!pedido.estornar()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só se estorna pedido pago e cancelado, uma vez");
        }
        String motivo = justificativa == null ? "" : justificativa.trim();
        auditor.registrar(usuario, pedido.getLojaId(), "PAGAMENTO_ESTORNADO", "Pedido", id, motivo.isEmpty() ? null : motivo);
        return repository.save(pedido);
    }

    /** Lê o pedido com trava, e só o da loja do usuário (outra loja é 403). */
    private Pedido carregar(Usuario usuario, Long id) {
        Pedido pedido = repository.findWithLockById(id).orElseThrow(() -> Acesso.naoExiste("Pedido"));
        Acesso.escopo(usuario, pedido.getLojaId());
        return pedido;
    }

    private static ResponseStatusException erro(String mensagem) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagem);
    }
}

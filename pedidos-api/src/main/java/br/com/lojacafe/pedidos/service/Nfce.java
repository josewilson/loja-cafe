package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.FormaPagamento;
import br.com.lojacafe.pedidos.model.ItemPedido;
import br.com.lojacafe.pedidos.model.Loja;
import br.com.lojacafe.pedidos.model.Pedido;
import br.com.lojacafe.pedidos.model.Produto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emissão de NFC-e (Fase 6b), a única integração fiscal. Três modos em NFCE_PROVEDOR:
 * <ul>
 *   <li>{@code desligado} (padrão): não emite nada;</li>
 *   <li>{@code simulado}: autoriza de mentira, sem internet, mas com as mesmas exigências do real (CNPJ, NCM e CFOP, prazo de 30 min para cancelar);</li>
 *   <li>{@code focus}: Focus NFe de verdade (homologação por padrão; produção só com FOCUS_AMBIENTE=producao).</li>
 * </ul>
 * Tributação (ICMS) é decisão do contador: NFCE_ICMS_SITUACAO e NFCE_ICMS_ORIGEM só têm valores padrão do Simples Nacional.
 */
@Component
public class Nfce {

    public record Resultado(String status, String numero, String chave, String url, String mensagem) {}

    /** O que a SEFAZ aceita para cancelar uma NFC-e (Focus NFe: "dentro de 30 minutos da emissão"). */
    public static final Duration PRAZO_CANCELAMENTO = Duration.ofMinutes(30);

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private final JsonMapper json;
    private final String provedor;
    private final String token;
    private final String base;
    private final String icmsSituacao;
    private final String icmsOrigem;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Nfce(JsonMapper json, @Value("${lojacafe.nfce.provedor}") String provedor, @Value("${lojacafe.nfce.focus.token:}") String token,
                @Value("${lojacafe.nfce.focus.ambiente}") String ambiente, @Value("${lojacafe.nfce.icms-situacao}") String icmsSituacao,
                @Value("${lojacafe.nfce.icms-origem}") String icmsOrigem) {
        this.json = json;
        this.provedor = provedor;
        this.token = token;
        this.icmsSituacao = icmsSituacao;
        this.icmsOrigem = icmsOrigem;
        if (!List.of("desligado", "simulado", "focus").contains(provedor)) {
            throw new IllegalStateException("NFCE_PROVEDOR inválido: " + provedor + " (desligado, simulado ou focus)");
        }
        this.base = switch (ambiente) {
            case "homologacao" -> "https://homologacao.focusnfe.com.br";
            case "producao" -> "https://api.focusnfe.com.br";
            default -> throw new IllegalStateException("FOCUS_AMBIENTE inválido: " + ambiente + " (homologacao ou producao)");
        };
        if (provedor.equals("focus") && token.isBlank()) {
            throw new IllegalStateException("NFCE_PROVEDOR=focus exige FOCUS_TOKEN (deploy/.env)");
        }
    }

    public boolean ligada() {
        return !provedor.equals("desligado");
    }

    /** Emite a NFC-e do pedido. Quem chama já conferiu loja (CNPJ) e produtos (NCM, CFOP). Falha do provedor vira 502. */
    public Resultado emitir(Pedido p, Loja loja, Map<Long, Produto> produtos) {
        if (provedor.equals("simulado")) {
            return new Resultado("AUTORIZADA", String.valueOf(p.getId()), String.format("%044d", p.getId()), "simulado://nfce/" + p.getId(),
                    "Autorizado (simulado)");
        }
        String ref = referencia(p);
        var resposta = chamar("POST", "/v2/nfce?ref=" + ref, json.writeValueAsString(corpo(p, loja, produtos)));
        if (resposta.status() == 422) {
            // Pode ser "referência já usada" (uma emissão anterior que respondeu tarde): se a nota existe e está autorizada, vale ela.
            var existente = interpretar(chamar("GET", "/v2/nfce/" + ref, null));
            if (existente.status().equals("AUTORIZADA")) {
                return existente;
            }
        }
        return interpretar(resposta);
    }

    /** Cancela a NFC-e já autorizada. Qualquer falha lança 409/502 e quem chama desfaz o cancelamento do pedido. */
    public void cancelar(Pedido p, String justificativa) {
        if (provedor.equals("simulado")) {
            return;
        }
        var resposta = chamar("DELETE", "/v2/nfce/" + referencia(p), json.writeValueAsString(Map.of("justificativa", justificativa)));
        if (resposta.status() != 200 || !"cancelado".equals(campo(resposta.corpo(), "status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A NFC-e não foi cancelada: " + mensagem(resposta.corpo()));
        }
    }

    // ---------- Focus NFe ----------

    record Resposta(int status, Map<String, Object> corpo) {}

    private static String referencia(Pedido p) {
        return "loja-cafe-" + p.getId();
    }

    private Map<String, Object> corpo(Pedido p, Loja loja, Map<Long, Produto> produtos) {
        List<Map<String, Object>> itens = new ArrayList<>();
        for (ItemPedido i : p.getItens()) {
            Produto produto = produtos.get(i.getProdutoId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("numero_item", String.valueOf(itens.size() + 1));
            item.put("codigo_produto", String.valueOf(i.getProdutoId()));
            item.put("descricao", i.getItem());
            item.put("codigo_ncm", produto.getNcm());
            item.put("cfop", produto.getCfop());
            item.put("unidade_comercial", "un");
            item.put("quantidade_comercial", i.getQuantidade());
            item.put("valor_unitario_comercial", i.getPrecoUnitario());
            item.put("unidade_tributavel", "un");
            item.put("quantidade_tributavel", i.getQuantidade());
            item.put("valor_unitario_tributavel", i.getPrecoUnitario());
            item.put("valor_bruto", i.getValor());
            item.put("icms_origem", icmsOrigem);
            item.put("icms_situacao_tributaria", icmsSituacao);
            itens.add(item);
        }
        Map<String, Object> pagamento = new LinkedHashMap<>();
        pagamento.put("forma_pagamento", codigoDaForma(p.getFormaPagamento()));
        pagamento.put("valor_pagamento", p.getValor());
        if (p.getFormaPagamento() == FormaPagamento.CREDITO || p.getFormaPagamento() == FormaPagamento.DEBITO) {
            pagamento.put("tipo_integracao", "2"); // não integrado: a maquininha é independente do sistema
        }
        Map<String, Object> nota = new LinkedHashMap<>();
        nota.put("cnpj_emitente", loja.getCnpj());
        nota.put("data_emissao", OffsetDateTime.now(FUSO).truncatedTo(ChronoUnit.SECONDS).toString());
        nota.put("natureza_operacao", "VENDA AO CONSUMIDOR");
        nota.put("presenca_comprador", "1");
        nota.put("modalidade_frete", "9");
        nota.put("local_destino", "1");
        nota.put("items", itens);
        nota.put("formas_pagamento", List.of(pagamento));
        return nota;
    }

    /** Códigos da tabela de meios de pagamento da NFC-e. ponytail: 17 (Pix) vem da NT 2020.006, confirmar em homologação com a Focus. */
    private static String codigoDaForma(FormaPagamento forma) {
        return switch (forma) {
            case DINHEIRO -> "01";
            case CREDITO -> "03";
            case DEBITO -> "04";
            case PIX -> "17";
        };
    }

    /** Traduz a resposta da Focus (emitir ou consultar) para o que o pedido guarda. Rejeição não é erro do sistema: a nota fica REJEITADA. */
    private Resultado interpretar(Resposta r) {
        if (r.status() == 200 || r.status() == 201) {
            if ("autorizado".equals(campo(r.corpo(), "status"))) {
                String danfe = campo(r.corpo(), "caminho_danfe");
                return new Resultado("AUTORIZADA", campo(r.corpo(), "numero"), campo(r.corpo(), "chave_nfe"),
                        danfe != null && danfe.startsWith("/") ? base + danfe : danfe, "Autorizado");
            }
            return new Resultado("REJEITADA", null, null, null, mensagem(r.corpo()));
        }
        if (r.status() == 400 || r.status() == 403 || r.status() == 404 || r.status() == 422) {
            return new Resultado("REJEITADA", null, null, null, mensagem(r.corpo()));
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Focus NFe respondeu " + r.status() + " (confira FOCUS_TOKEN)");
    }

    private static String campo(Map<String, Object> corpo, String nome) {
        Object valor = corpo.get(nome);
        return valor == null ? null : String.valueOf(valor);
    }

    private static String mensagem(Map<String, Object> corpo) {
        String texto = campo(corpo, "mensagem_sefaz");
        if (texto == null) {
            texto = campo(corpo, "mensagem");
        }
        String limite = texto == null ? "sem mensagem do provedor" : texto;
        return limite.length() > 240 ? limite.substring(0, 240) : limite;
    }

    /** A única chamada de rede (separada para os testes trocarem por uma resposta pronta). HTTP Basic: o token é o usuário, senha vazia. */
    @SuppressWarnings("unchecked")
    protected Resposta chamar(String metodo, String caminho, String corpo) {
        var pedido = HttpRequest.newBuilder(URI.create(base + caminho)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((token + ":").getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        try {
            var resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> dados;
            try {
                dados = json.readValue(resposta.body(), Map.class);
            } catch (RuntimeException e) {
                dados = Map.of(); // 401 volta HTML, por exemplo
            }
            return new Resposta(resposta.statusCode(), dados);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Focus NFe fora do ar ou sem resposta");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Focus NFe: chamada interrompida");
        }
    }
}

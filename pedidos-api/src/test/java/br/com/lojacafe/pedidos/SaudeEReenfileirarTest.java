package br.com.lojacafe.pedidos;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import br.com.lojacafe.pedidos.controller.SaudeController;
import br.com.lojacafe.pedidos.model.Insumo;
import br.com.lojacafe.pedidos.model.Loja;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Pedido;
import br.com.lojacafe.pedidos.model.Produto;
import br.com.lojacafe.pedidos.service.PagamentosKafka;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class SaudeEReenfileirarTest extends ApiTestBase {

    // ---------- saúde da API (monitoramento e Docker) ----------

    @Test
    void saudeEPublicaEComTudoVivoDa200() throws Exception {
        org.mockito.Mockito.doReturn(true).when(saude).kafkaVivo(); // nos testes não há broker de verdade
        GET(null, "/saude").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.banco").value("ok"))
                .andExpect(jsonPath("$.kafka").value("ok"))
                .andExpect(jsonPath("$.outbox").value("ok")); // fila vazia no H2: consulta real
    }

    @Test
    void outboxAtrasadaDa503() throws Exception {
        org.mockito.Mockito.doReturn(true).when(saude).kafkaVivo();
        org.mockito.Mockito.doReturn(false).when(saude).outboxEmDia();
        GET(null, "/saude").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("falha"))
                .andExpect(jsonPath("$.kafka").value("ok"))
                .andExpect(jsonPath("$.outbox").value("atrasada"));
    }

    @Test
    void saudeSemKafkaDa503ComOBancoOk() throws Exception {
        org.mockito.Mockito.doReturn(false).when(saude).kafkaVivo();
        GET(null, "/saude").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("falha"))
                .andExpect(jsonPath("$.banco").value("ok"))
                .andExpect(jsonPath("$.kafka").value("falha"));
    }

    @Test
    void saudeSemBancoDa503() throws Exception {
        org.mockito.Mockito.doReturn(false).when(saude).bancoVivo();
        org.mockito.Mockito.doReturn(true).when(saude).kafkaVivo();
        GET(null, "/saude").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.banco").value("falha"))
                .andExpect(jsonPath("$.kafka").value("ok"));
    }

    @Test
    void oBancoRealDosTestesResponde() {
        org.junit.jupiter.api.Assertions.assertTrue(saude.bancoVivo()); // consulta de verdade ao H2
    }

    @Test
    void saudeNaoVazaDetalhesInternos() throws Exception {
        org.mockito.Mockito.doReturn(false).when(saude).kafkaVivo();
        String corpo = GET(null, "/saude").andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(corpo.contains("jdbc") || corpo.contains("localhost") || corpo.contains("Exception"), corpo);
    }

    // ---------- reenviar pedidos parados ao Pagamentos (depois de restaurar um backup) ----------

    private java.util.Set<Long> idsReenviados() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(Pedido.class);
        org.mockito.Mockito.verify(eventos, org.mockito.Mockito.atLeast(0)).publicarCriado(captor.capture());
        return captor.getAllValues().stream().map(Pedido::getId).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void reenfileirarReenviaSoOsPedidosQueAindaAguardam() throws Exception {
        String atendente = token(Papel.ATENDENTE, novaLoja());
        long parado1 = lancar(atendente, 1, 1);
        long parado2 = lancar(atendente, 1, 1);
        long pago = lancarPago(atendente, 1, 1);
        long recusado = lancar(atendente, 1, 1);
        resultado(recusado, "RECUSADO");
        org.mockito.Mockito.clearInvocations(eventos); // ignora os envios feitos ao lançar os pedidos

        POST(admin(), "/pedidos/reenfileirar?minutos=0", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.reenviados", greaterThanOrEqualTo(2))).andExpect(jsonPath("$.minutos").value(0));
        var reenviados = idsReenviados();
        org.junit.jupiter.api.Assertions.assertTrue(reenviados.contains(parado1) && reenviados.contains(parado2), "reenviados: " + reenviados);
        org.junit.jupiter.api.Assertions.assertFalse(reenviados.contains(pago), "pedido já pago não volta para o Pagamentos");
        org.junit.jupiter.api.Assertions.assertFalse(reenviados.contains(recusado), "pedido já recusado não volta para o Pagamentos");
    }

    @Test
    void reenfileirarRespeitaOTempoMinimoDeEspera() throws Exception {
        String atendente = token(Papel.ATENDENTE, novaLoja());
        long recem = lancar(atendente, 1, 1); // acabou de nascer: ainda não está "parado"
        org.mockito.Mockito.clearInvocations(eventos);
        POST(admin(), "/pedidos/reenfileirar", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.minutos").value(10)); // padrão: 10 min
        org.junit.jupiter.api.Assertions.assertFalse(idsReenviados().contains(recem));
    }

    @Test
    void reenfileirarSoOAdminFazEValidaOLimite() throws Exception {
        long loja = novaLoja();
        for (String t : new String[]{token(Papel.GERENTE, loja), token(Papel.ATENDENTE, loja), token(Papel.COZINHA, loja)}) {
            POST(t, "/pedidos/reenfileirar?minutos=0", "{}").andExpect(status().isForbidden());
        }
        POST(null, "/pedidos/reenfileirar?minutos=0", "{}").andExpect(status().isUnauthorized());
        POST(admin(), "/pedidos/reenfileirar?minutos=-1", "{}").andExpect(status().isBadRequest());
        POST(admin(), "/pedidos/reenfileirar?minutos=10081", "{}").andExpect(status().isBadRequest());
        POST(admin(), "/pedidos/reenfileirar?minutos=abc", "{}").andExpect(status().isBadRequest());
        POST(admin(), "/pedidos/reenfileirar?minutos=10080", "{}").andExpect(status().isOk());
    }

    @Test
    void reenfileirarVaiParaAAuditoria() throws Exception {
        POST(admin(), "/pedidos/reenfileirar?minutos=0", "{}").andExpect(status().isOk());
        GET(admin(), "/auditoria?limite=50").andExpect(jsonPath("$[?(@.acao=='PEDIDOS_REENFILEIRADOS')].detalhe",
                hasItem(containsString("aguardando há mais de 0 min"))));
    }
}

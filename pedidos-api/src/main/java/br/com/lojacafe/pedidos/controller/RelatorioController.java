package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.config.AuthInterceptor;
import br.com.lojacafe.pedidos.config.AuthInterceptor.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Vendas de um dia ou de um período da loja (specs/api.md). Regras em {@link RelatorioService}. */
@RestController
@RequestMapping("/relatorios")
public class RelatorioController {

    private final RelatorioService relatorios;

    public RelatorioController(RelatorioService relatorios) {
        this.relatorios = relatorios;
    }

    @GetMapping("/vendas")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public RelatorioService.Vendas vendas(@RequestAttribute(AuthInterceptor.USUARIO) Usuario usuario, @RequestParam(required = false) String data,
                                          @RequestParam(required = false) String ate, @RequestParam(required = false) Long lojaId) {
        return relatorios.vendas(usuario, data, ate, lojaId);
    }

    /** Uma linha por venda do período, para o contador (Excel brasileiro: separador ";" e vírgula decimal). */
    @GetMapping("/vendas.csv")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public ResponseEntity<String> vendasCsv(@RequestAttribute(AuthInterceptor.USUARIO) Usuario usuario, @RequestParam(required = false) String data,
                                            @RequestParam(required = false) String ate, @RequestParam(required = false) Long lojaId) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(relatorios.vendasCsv(usuario, data, ate, lojaId));
    }
}

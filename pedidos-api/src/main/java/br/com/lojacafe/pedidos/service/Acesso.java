package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Usuario;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Ajudas pequenas que controllers e serviços usam: token e hash, escopo de loja, erros padrão e paginação. */
public final class Acesso {

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private Acesso() {}

    /** Token de 256 bits (sessão e link de nova senha). */
    public static String tokenAleatorio() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** O banco guarda só o hash do token. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 existe em todo JDK
        }
    }

    /**
     * Qual loja a requisição enxerga. Funcionário de loja: sempre a dele (pedir outra é 403).
     * ADMIN: a que ele escolher pela query; sem escolha, devolve nulo (= todas).
     */
    public static Long escopo(Usuario usuario, Long lojaPedida) {
        if (usuario.getPapel() == Papel.ADMIN) {
            return lojaPedida;
        }
        if (lojaPedida != null && !lojaPedida.equals(usuario.getLojaId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só acessa a sua loja");
        }
        return usuario.getLojaId();
    }

    /** 404 padrão: "Pedido não existe". */
    public static ResponseStatusException naoExiste(String o) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, o + " não existe");
    }

    /** 409 padrão: "Já existe um produto com este nome". */
    public static ResponseStatusException nomeRepetido(String o) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um " + o + " com este nome");
    }

    /** Primeira página de uma lista, com o limite do cliente preso entre 1 e `max`. */
    public static PageRequest pagina(int limite, int max) {
        return PageRequest.of(0, Math.max(1, Math.min(limite, max)));
    }
}

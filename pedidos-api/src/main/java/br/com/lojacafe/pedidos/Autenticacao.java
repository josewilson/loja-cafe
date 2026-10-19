package br.com.lojacafe.pedidos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Porteiro de toda rota (menos /auth/entrar e /opcoes): exige "Authorization: Bearer <token>" de uma conta ativa,
 * confere o papel pedido em @Papeis e entrega o usuário ao controller.
 */
@Component
public class Autenticacao implements HandlerInterceptor {

    public static final String USUARIO = "usuario";
    public static final String TOKEN = "tokenHash";

    private final SessaoRepository sessoes;

    public Autenticacao(SessaoRepository sessoes) {
        this.sessoes = sessoes;
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

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) {
            return true; // pré-voo do CORS não leva token
        }
        String cabecalho = req.getHeader("Authorization");
        if (cabecalho != null && cabecalho.startsWith("Bearer ")) {
            String tokenHash = hash(cabecalho.substring(7));
            var sessao = sessoes.findById(tokenHash);
            if (sessao.isPresent() && sessao.get().getExpiraEm().isAfter(Instant.now()) && sessao.get().getUsuario().isAtivo()) {
                Usuario usuario = sessao.get().getUsuario();
                if (handler instanceof HandlerMethod metodo) {
                    Papeis exigidos = metodo.getMethodAnnotation(Papeis.class);
                    if (exigidos != null && !Arrays.asList(exigidos.value()).contains(usuario.getPapel())) {
                        res.sendError(HttpServletResponse.SC_FORBIDDEN, "Seu papel não permite isto");
                        return false;
                    }
                }
                req.setAttribute(USUARIO, usuario);
                req.setAttribute(TOKEN, tokenHash);
                return true;
            }
        }
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Faça login para continuar");
        return false;
    }
}

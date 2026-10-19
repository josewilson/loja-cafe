package br.com.lojacafe.pedidos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Exige "Authorization: Bearer <token>" nas rotas de pedidos e entrega o usuário ao controller. */
@Component
public class Autenticacao implements HandlerInterceptor {

    public static final String USUARIO = "usuario";

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

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) {
            return true; // pré-voo do CORS não leva token
        }
        String cabecalho = req.getHeader("Authorization");
        if (cabecalho != null && cabecalho.startsWith("Bearer ")) {
            var sessao = sessoes.findById(hash(cabecalho.substring(7)));
            if (sessao.isPresent() && sessao.get().getExpiraEm().isAfter(Instant.now())) {
                req.setAttribute(USUARIO, sessao.get().getUsuario());
                return true;
            }
        }
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Faça login para continuar");
        return false;
    }
}

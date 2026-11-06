package br.com.lojacafe.pedidos.config;

import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.service.Acesso;
import br.com.lojacafe.pedidos.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * O porteiro de toda rota (specs/api.md): exige "Authorization: Bearer <token>" de uma conta ativa, confere o papel pedido
 * em @Papeis e entrega o usuário ao controller. Quais rotas ficam de fora está em {@link WebConfig}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** Quem pode chamar este endpoint. Sem a anotação, qualquer usuário logado pode. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Papeis {
        Papel[] value();
    }

    public static final String USUARIO = "usuario";
    public static final String TOKEN = "tokenHash";

    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) {
            return true; // pré-voo do CORS não leva token
        }
        String cabecalho = req.getHeader("Authorization");
        if (cabecalho != null && cabecalho.startsWith("Bearer ")) {
            String tokenHash = Acesso.hash(cabecalho.substring(7));
            Usuario usuario = auth.usuarioDaSessao(tokenHash).orElse(null);
            if (usuario != null) {
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

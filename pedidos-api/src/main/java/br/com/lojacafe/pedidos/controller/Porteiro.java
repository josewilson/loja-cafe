package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.SessaoRepository;
import br.com.lojacafe.pedidos.service.Acesso;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * O porteiro de toda rota (specs/api.md): exige "Authorization: Bearer <token>" de uma conta ativa (menos /auth/entrar,
 * /auth/redefinir-senha, /opcoes, /saude e /error), confere o papel pedido em @Papeis e entrega o usuário ao controller.
 * Também liga o CORS e os cabeçalhos de segurança.
 */
@Component
public class Porteiro implements HandlerInterceptor, WebMvcConfigurer {

    /** Quem pode chamar este endpoint. Sem a anotação, qualquer usuário logado pode. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Papeis {
        Papel[] value();
    }

    public static final String USUARIO = "usuario";
    public static final String TOKEN = "tokenHash";

    // Em outro ambiente: lojacafe.cors.origem=https://meu-site (propriedade ou variável de ambiente).
    @Value("${lojacafe.cors.origem:http://localhost:5173}")
    private String origem;

    private final SessaoRepository sessoes;

    public Porteiro(SessaoRepository sessoes) {
        this.sessoes = sessoes;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) {
            return true; // pré-voo do CORS não leva token
        }
        String cabecalho = req.getHeader("Authorization");
        if (cabecalho != null && cabecalho.startsWith("Bearer ")) {
            String tokenHash = Acesso.hash(cabecalho.substring(7));
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

    /** Libera o front (Vite, porta 5173). */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(origem).allowedMethods("GET", "POST", "PUT").allowedHeaders("Authorization", "Content-Type");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Os cabeçalhos vêm primeiro: se o porteiro barrar (401/403), a resposta de erro também sai com eles.
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("X-Frame-Options", "DENY");
                res.setHeader("Referrer-Policy", "no-referrer");
                res.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
                return true;
            }
        });
        // Tudo exige login, menos o próprio login, as opções (tamanhos/extras/pagamentos), a saúde da API e a página de erro.
        registry.addInterceptor(this).addPathPatterns("/**").excludePathPatterns("/auth/entrar", "/auth/redefinir-senha", "/opcoes", "/saude", "/error");
    }
}

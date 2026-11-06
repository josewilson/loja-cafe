package br.com.lojacafe.pedidos.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Liga o CORS, os cabeçalhos de segurança e o {@link AuthInterceptor} às rotas. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    // Em outro ambiente: lojacafe.cors.origem=https://meu-site (propriedade ou variável de ambiente).
    @Value("${lojacafe.cors.origem:http://localhost:5173}")
    private String origem;

    private final AuthInterceptor auth;

    public WebConfig(AuthInterceptor auth) {
        this.auth = auth;
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
        registry.addInterceptor(auth).addPathPatterns("/**").excludePathPatterns("/auth/entrar", "/auth/redefinir-senha", "/opcoes", "/saude", "/error");
    }
}

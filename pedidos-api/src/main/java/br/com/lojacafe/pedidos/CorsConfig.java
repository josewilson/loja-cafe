package br.com.lojacafe.pedidos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Libera o front (Vite, porta 5173) e acrescenta cabeçalhos de segurança básicos às respostas. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // Em outro ambiente: lojacafe.cors.origem=https://meu-site (propriedade ou variável de ambiente).
    @Value("${lojacafe.cors.origem:http://localhost:5173}")
    private String origem;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(origem).allowedMethods("GET", "POST");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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
    }
}

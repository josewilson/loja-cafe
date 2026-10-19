package br.com.lojacafe.pedidos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Primeira partida sem nenhum ADMIN: cria um com ADMIN_EMAIL e ADMIN_PASSWORD (infra/.env). Nunca há senha fixa no código. */
@Component
public class AdminInicial implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInicial.class);

    private final UsuarioRepository usuarios;
    private final String email;
    private final String senha;

    public AdminInicial(UsuarioRepository usuarios,
                        @Value("${lojacafe.admin.email:}") String email,
                        @Value("${lojacafe.admin.senha:}") String senha) {
        this.usuarios = usuarios;
        this.email = email;
        this.senha = senha;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (usuarios.existsByPapel(Papel.ADMIN)) {
            return;
        }
        if (email.isBlank() || senha.length() < 8) {
            log.warn("Nenhum ADMIN cadastrado. Defina ADMIN_EMAIL e ADMIN_PASSWORD (mínimo 8 caracteres) em infra/.env e reinicie.");
            return;
        }
        usuarios.save(new Usuario("Administrador", email, new BCryptPasswordEncoder().encode(senha), Papel.ADMIN, null));
        log.info("ADMIN inicial criado: {}", email.trim().toLowerCase());
    }
}

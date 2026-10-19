package br.com.lojacafe.pedidos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Primeira partida sem nenhum ADMIN: cria um com ADMIN_EMAIL e ADMIN_PASSWORD (infra/.env). Nunca há senha fixa no código.
 * Roda quando os beans terminam de nascer, ANTES de o servidor web aceitar a primeira requisição (um ApplicationRunner
 * rodaria depois: dava uma janela em que a API já respondia, mas o ADMIN ainda não existia).
 */
@Component
public class AdminInicial implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(AdminInicial.class);

    private final UsuarioRepository usuarios;
    private final BCryptPasswordEncoder bcrypt;
    private final String email;
    private final String senha;

    public AdminInicial(UsuarioRepository usuarios, BCryptPasswordEncoder bcrypt,
                        @Value("${lojacafe.admin.email:}") String email,
                        @Value("${lojacafe.admin.senha:}") String senha) {
        this.usuarios = usuarios;
        this.bcrypt = bcrypt;
        this.email = email;
        this.senha = senha;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (usuarios.existsByPapel(Papel.ADMIN)) {
            return;
        }
        if (email.isBlank() || senha.length() < 8) {
            log.warn("Nenhum ADMIN cadastrado. Defina ADMIN_EMAIL e ADMIN_PASSWORD (mínimo 8 caracteres) em infra/.env e reinicie.");
            return;
        }
        usuarios.save(new Usuario("Administrador", email, bcrypt.encode(senha), Papel.ADMIN, null));
        log.info("ADMIN inicial criado: {}", email.trim().toLowerCase());
    }
}

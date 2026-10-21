package br.com.lojacafe.pedidos.repository;

import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Usuario;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByEmail(String email);

    Optional<Usuario> findByRedefinicaoHash(String tokenHash);

    boolean existsByPapel(Papel papel);

    List<Usuario> findAllByOrderByIdAsc();

    List<Usuario> findByLoja_IdOrderByIdAsc(Long lojaId);
}

package br.com.lojacafe.pedidos;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByEmail(String email);

    boolean existsByPapel(Papel papel);

    List<Usuario> findAllByOrderByIdAsc();

    List<Usuario> findByLoja_IdOrderByIdAsc(Long lojaId);
}

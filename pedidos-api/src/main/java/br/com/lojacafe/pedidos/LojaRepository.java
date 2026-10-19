package br.com.lojacafe.pedidos;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LojaRepository extends JpaRepository<Loja, Long> {
    Optional<Loja> findByNome(String nome);
}

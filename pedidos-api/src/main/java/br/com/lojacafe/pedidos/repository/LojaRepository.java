package br.com.lojacafe.pedidos.repository;

import br.com.lojacafe.pedidos.model.Loja;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LojaRepository extends JpaRepository<Loja, Long> {
    Optional<Loja> findByNome(String nome);
}

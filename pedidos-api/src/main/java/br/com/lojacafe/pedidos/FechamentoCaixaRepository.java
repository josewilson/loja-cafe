package br.com.lojacafe.pedidos;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FechamentoCaixaRepository extends JpaRepository<FechamentoCaixa, Long> {
    Optional<FechamentoCaixa> findFirstByLojaIdOrderByIdDesc(Long lojaId);

    List<FechamentoCaixa> findByLojaIdOrderByIdDesc(Long lojaId, Pageable limite);

    List<FechamentoCaixa> findAllByOrderByIdDesc(Pageable limite);
}

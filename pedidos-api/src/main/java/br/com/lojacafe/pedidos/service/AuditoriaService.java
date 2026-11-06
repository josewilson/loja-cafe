package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.Auditoria;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.AuditoriaRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuditoriaService {

    private final AuditoriaRepository repository;

    public AuditoriaService(AuditoriaRepository repository) {
        this.repository = repository;
    }

    /** Do mais novo ao mais antigo (padrão 100, de 1 a 500). O gerente vê só a sua loja; o ADMIN vê tudo (ou filtra por lojaId). */
    public List<Auditoria> listar(Usuario quem,
                                  int limite,
                                  Long lojaId) {
        var pagina = Acesso.pagina(limite, 500);
        Long loja = Acesso.escopo(quem, lojaId);
        return loja == null ? repository.findAllByOrderByIdDesc(pagina) : repository.findByLojaIdOrderByIdDesc(loja, pagina);
    }
}

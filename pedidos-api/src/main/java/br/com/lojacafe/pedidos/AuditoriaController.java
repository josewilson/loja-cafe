package br.com.lojacafe.pedidos;

import br.com.lojacafe.pedidos.AuthController.Papeis;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auditoria")
public class AuditoriaController {

    private final AuditoriaRepository repository;

    public AuditoriaController(AuditoriaRepository repository) {
        this.repository = repository;
    }

    /** Do mais novo ao mais antigo (padrão 100, de 1 a 500). O gerente vê só a sua loja; o ADMIN vê tudo (ou filtra por lojaId). */
    @GetMapping
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<Auditoria> listar(@RequestAttribute(AuthController.USUARIO) Usuario quem,
                                  @RequestParam(defaultValue = "100") int limite,
                                  @RequestParam(required = false) Long lojaId) {
        var pagina = PageRequest.of(0, Math.max(1, Math.min(limite, 500)));
        Long loja = AuthController.escopo(quem, lojaId);
        return loja == null ? repository.findAllByOrderByIdDesc(pagina) : repository.findByLojaIdOrderByIdDesc(loja, pagina);
    }
}

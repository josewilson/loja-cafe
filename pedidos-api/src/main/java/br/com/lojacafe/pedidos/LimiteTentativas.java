package br.com.lojacafe.pedidos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Freio simples contra força bruta: conta ocorrências por chave dentro de uma janela de tempo.
 * ponytail: fica na memória desta instância; com várias instâncias da API, trocar por contador compartilhado (Redis/banco).
 */
@Component
public class LimiteTentativas {

    private final Map<String, Deque<Instant>> ocorrencias = new ConcurrentHashMap<>();

    /** Já houve `max` ou mais ocorrências da chave dentro da janela? */
    public boolean estourou(String chave, int max, Duration janela) {
        Deque<Instant> fila = ocorrencias.get(chave);
        if (fila == null) {
            return false;
        }
        synchronized (fila) {
            descartarVelhas(fila, janela);
            return fila.size() >= max;
        }
    }

    public void registrar(String chave) {
        Deque<Instant> fila = ocorrencias.computeIfAbsent(chave, k -> new ArrayDeque<>());
        synchronized (fila) {
            fila.addLast(Instant.now());
        }
    }

    public void zerar(String chave) {
        ocorrencias.remove(chave);
    }

    /** Chamado pela manutenção: esquece quem não tem ocorrência recente, para o mapa não crescer para sempre. */
    public void limpar(Duration janela) {
        ocorrencias.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                descartarVelhas(e.getValue(), janela);
                return e.getValue().isEmpty();
            }
        });
    }

    private static void descartarVelhas(Deque<Instant> fila, Duration janela) {
        Instant limite = Instant.now().minus(janela);
        while (!fila.isEmpty() && fila.peekFirst().isBefore(limite)) {
            fila.removeFirst();
        }
    }
}

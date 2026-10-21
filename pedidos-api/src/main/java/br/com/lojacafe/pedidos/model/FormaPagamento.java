package br.com.lojacafe.pedidos.model;

/** Como o cliente paga. O Pagamentos (Python) aplica a regra de cada uma (specs/api.md). */
public enum FormaPagamento {
    PIX("Pix", 1),
    CREDITO("Cartão de crédito", 3),
    DEBITO("Cartão de débito", 1),
    DINHEIRO("Dinheiro no balcão", 1);

    private final String nome;
    private final int parcelasMax;

    FormaPagamento(String nome, int parcelasMax) {
        this.nome = nome;
        this.parcelasMax = parcelasMax;
    }

    public String getNome() { return nome; }
    public int getParcelasMax() { return parcelasMax; }
}

package br.com.lojacafe.pedidos.model;

import jakarta.persistence.*;

/** Uma loja da rede. */
@Entity
public class Loja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private boolean ativa = true;
    // Dados fiscais (Fase 6), todos opcionais: aparecem no recibo.
    private String razaoSocial;
    private String cnpj;
    private String inscricaoEstadual;
    private String endereco;

    protected Loja() {
        // exigido pelo JPA
    }

    public Loja(String nome) {
        this.nome = nome.trim();
    }

    /** Campo em branco vira nulo (= "não informado"). */
    public void atualizarFiscal(String razaoSocial, String cnpj, String inscricaoEstadual, String endereco) {
        this.razaoSocial = vazioViraNulo(razaoSocial);
        this.cnpj = vazioViraNulo(cnpj);
        this.inscricaoEstadual = vazioViraNulo(inscricaoEstadual);
        this.endereco = vazioViraNulo(endereco);
    }

    private static String vazioViraNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    /** 14 dígitos e os dois dígitos verificadores certos (módulo 11). */
    public static boolean cnpjValido(String c) {
        if (c == null || !c.matches("\\d{14}") || c.chars().distinct().count() == 1) {
            return false;
        }
        return digito(c, new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}) == c.charAt(12) - '0'
                && digito(c, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}) == c.charAt(13) - '0';
    }

    private static int digito(String c, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (c.charAt(i) - '0') * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public boolean isAtiva() { return ativa; }
    public String getRazaoSocial() { return razaoSocial; }
    public String getCnpj() { return cnpj; }
    public String getInscricaoEstadual() { return inscricaoEstadual; }
    public String getEndereco() { return endereco; }
}

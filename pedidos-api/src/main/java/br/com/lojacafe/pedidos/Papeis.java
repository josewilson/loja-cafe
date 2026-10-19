package br.com.lojacafe.pedidos;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Quem pode chamar este endpoint. Sem a anotação, qualquer usuário logado pode. Conferido pela Autenticacao. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Papeis {
    Papel[] value();
}

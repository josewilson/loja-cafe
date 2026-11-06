package br.com.lojacafe.pedidos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Trava as camadas: controller → service → repository. Quem quebrar a regra quebra o build. */
@AnalyzeClasses(packages = "br.com.lojacafe.pedidos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    @ArchTest
    static final ArchRule controllersNaoAcessamRepositorios = noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule servicosNaoDependemDeControllers = noClasses().that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule repositoriosNaoDependemDeServicos = noClasses().that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAPackage("..service..");
}

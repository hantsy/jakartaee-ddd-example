package library.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import library.catalog.application.DomainEventListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture tests guarding the DDD layered architecture.
 * <p>
 * These rules encode the intended dependency rules of the application:
 * <ul>
 *   <li>the <em>domain</em> layer is the innermost layer and depends on nothing
 *       outside itself and the shared kernel ({@code library.common});</li>
 *   <li>the <em>application</em> layer depends on the domain layer only —
 *       never on infrastructure (dependency inversion);</li>
 *   <li>the two bounded contexts ({@code library.catalog} and
 *       {@code library.lending}) must not depend on each other, with a single
 *       documented exception: the catalog observes lending domain events to
 *       update copy availability;</li>
 *   <li>repository contracts live in the domain layer.</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("library");
    }

    @Test
    void domainLayerMustNotDependOnApplicationOrInfrastructure() {
        ArchRule rule = noClasses().that().resideInAnyPackage("library..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("library..application..", "library..infrastructure..")
                .because("the domain layer is the innermost layer and must stay free of outer-layer concerns");
        rule.check(classes);
    }

    @Test
    void domainLayerMustStayFreeOfApplicationFrameworks() {
        ArchRule rule = noClasses().that().resideInAnyPackage("library..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.ws.rs..", "jakarta.enterprise..", "jakarta.inject..")
                .because("the domain model must remain independent of CDI, REST and other application frameworks");
        rule.check(classes);
    }

    @Test
    void applicationLayerMustNotDependOnInfrastructure() {
        ArchRule rule = noClasses().that().resideInAnyPackage("library..application..")
                .should().dependOnClassesThat().resideInAnyPackage("library..infrastructure..")
                .because("application services depend on domain ports; the infrastructure adapters implement them (dependency inversion)");
        rule.check(classes);
    }

    @Test
    void applicationLayerMustDependOnDomain() {
        ArchRule rule = classes().that().resideInAnyPackage("library..application..")
                .should().dependOnClassesThat().resideInAnyPackage("library..domain..")
                .because("application services orchestrate the domain model through its interfaces and aggregates");
        rule.check(classes);
    }

    @Test
    void sharedKernelMustNotDependOnBoundedContexts() {
        ArchRule rule = noClasses().that().resideInAPackage("library.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("library.catalog..", "library.lending..")
                .because("library.common is the shared kernel and must not know the bounded contexts");
        rule.check(classes);
    }

    @Test
    void lendingContextMustNotDependOnCatalogContext() {
        ArchRule rule = noClasses().that().resideInAnyPackage("library.lending..")
                .should().dependOnClassesThat().resideInAnyPackage("library.catalog..")
                .because("the lending bounded context must not depend on the catalog bounded context");
        rule.check(classes);
    }

    @Test
    void catalogContextMustNotDependOnLendingContextExceptObservedEvents() {
        ArchRule rule = noClasses().that().resideInAnyPackage("library.catalog..")
                .and().doNotBelongToAnyOf(DomainEventListener.class)
                .should().dependOnClassesThat().resideInAnyPackage("library.lending..")
                .because("the only allowed coupling between the contexts is the catalog observing lending "
                        + "domain events (LoanCreated/LoanClosed) via DomainEventListener to update copy availability");
        rule.check(classes);
    }

    @Test
    void repositoryContractsMustResideInDomainLayer() {
        ArchRule rule = classes().that().haveSimpleNameEndingWith("Repository")
                .should().resideInAPackage("..domain..")
                .because("a Repository is a domain contract; its implementation belongs to the infrastructure layer");
        rule.check(classes);
    }
}
package com.wex.fx.domain.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture gate (Phase 2, constitution &sect;7): the domain core is the crown jewel and must stay
 * <strong>framework-free</strong> so it is exhaustively unit-testable and the
 * {@code ExchangeRateProvider} seam stays honest. Adapters depend inward onto the domain, never the
 * reverse. Enforced with ArchUnit so the boundary can't silently rot.
 */
@AnalyzeClasses(packages = "com.wex.fx", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainArchitectureTest {

    @ArchTest
    static final ArchRule domain_has_no_framework_imports =
            noClasses().that().resideInAPackage("com.wex.fx.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "javax.persistence..",
                            "org.hibernate..",
                            "com.fasterxml.jackson..",
                            "org.flywaydb..",
                            "org.testcontainers..")
                    .because("the domain (money, rate-selection, validation, currency) must not depend "
                            + "on Spring, web, persistence, or JSON frameworks (constitution §7)");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_adapters_or_web =
            noClasses().that().resideInAPackage("com.wex.fx.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.wex.fx.adapter..",
                            "com.wex.fx.web..",
                            "com.wex.fx.config..")
                    .because("dependencies point inward toward the domain, never outward");
}

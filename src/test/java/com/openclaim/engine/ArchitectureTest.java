package com.openclaim.engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the boundaries described in the README: FHIR stays at the edge, the domain stays framework-light. */
@AnalyzeClasses(packages = "com.openclaim.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] HAPI = {"org.hl7.fhir..", "ca.uhn.fhir.."};

    @ArchTest
    static final ArchRule domainIsIndependentOfFhir = noClasses()
            .that()
            .resideInAnyPackage("..claim..", "..rules..", "..ledger..", "..ingest..", "..remittance..", "..outbox..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(HAPI)
            .because(
                    "FHIR is an interchange format mapped at the edge (fhir, web); business rules use the canonical model");

    @ArchTest
    static final ArchRule domainIsIndependentOfTheWebLayer = noClasses()
            .that()
            .resideInAnyPackage("..claim..", "..rules..", "..ledger..", "..ingest..", "..remittance..", "..outbox..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web.bind..",
                    "org.springframework.web.servlet..",
                    "org.springframework.web.context..",
                    "jakarta.servlet..",
                    "..engine.web..")
            .because("serving HTTP belongs to controllers; calling the payer with RestClient is allowed");

    @ArchTest
    static final ArchRule coreDomainHasNoOutwardDependencies = noClasses()
            .that()
            .resideInAnyPackage("..claim..", "..ledger..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..engine.rules..",
                    "..engine.ingest..",
                    "..engine.remittance..",
                    "..engine.outbox..",
                    "..engine.fhir..",
                    "..engine.config..")
            .because("claim and ledger are the innermost model");

    @ArchTest
    static final ArchRule rulesArePure = noClasses()
            .that()
            .resideInAPackage("..rules.validators..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.jdbc..", "org.springframework.data..", "java.sql..")
            .because("rules evaluate a pre-loaded fee schedule snapshot and never query the database");

    @ArchTest
    static final ArchRule controllersAreNotUsedByOtherLayers = classes()
            .that()
            .resideInAPackage("..engine.web..")
            .should()
            .onlyHaveDependentClassesThat()
            .resideInAPackage("..engine.web..");

    @ArchTest
    static final ArchRule noPackageCycles =
            slices().matching("com.openclaim.engine.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule ledgerIsWrittenOnlyThroughTheLedger = noClasses()
            .that()
            .resideOutsideOfPackage("..engine.ledger..")
            .should()
            .accessClassesThat()
            .haveSimpleName("Posting")
            .because("journals are built balanced by factory methods; postings are an implementation detail");
}

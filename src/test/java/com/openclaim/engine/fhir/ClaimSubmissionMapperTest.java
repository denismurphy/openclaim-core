package com.openclaim.engine.fhir;

import static com.openclaim.engine.fhir.FhirFixtures.claimBundle;
import static com.openclaim.engine.fhir.FhirFixtures.claimOf;
import static com.openclaim.engine.fhir.FhirFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openclaim.engine.claim.ClaimIdentifier;
import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.claim.Coding;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Consumer;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Condition;
import org.hl7.fhir.r5.model.Coverage;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.Encounter;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Money;
import org.hl7.fhir.r5.model.OperationOutcome.IssueType;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimSubmissionMapperTest {

    private static final String PROCEDURES = "https://openclaim.example.org/CodeSystem/procedure";
    private static final String DIAGNOSES = "https://openclaim.example.org/CodeSystem/diagnosis";

    private final ClaimSubmissionMapper mapper = new ClaimSubmissionMapper(FhirFixtures.properties());

    private ClaimSubmission mapWith(Consumer<Bundle> change) {
        Bundle bundle = claimBundle();
        change.accept(bundle);
        return mapper.map(bundle);
    }

    private void assertRejected(Consumer<Bundle> change, IssueType type, String expression, String message) {
        assertThatThrownBy(() -> mapWith(change)).isInstanceOfSatisfying(FhirMappingException.class, e -> {
            assertThat(e.issueType()).isEqualTo(type);
            assertThat(e.expression()).isEqualTo(expression);
            assertThat(e.getMessage()).contains(message);
        });
    }

    @Test
    void mapsTheReferenceBundle() {
        ClaimSubmission submission = mapper.map(claimBundle());

        assertThat(submission.identifier())
                .isEqualTo(new ClaimIdentifier("https://ehr.example.org/claims", "CLM-2026-0001"));
        assertThat(submission.patientMrn()).isEqualTo("MRN-0001");
        assertThat(submission.payerCode()).isEqualTo("ACME-HEALTH");
        assertThat(submission.policyNumber()).isEqualTo("POL123456");
        assertThat(submission.coverageKind()).isEqualTo("insurance");
        assertThat(submission.serviceStart()).isEqualTo(Instant.parse("2026-09-01T08:30:00Z"));
        assertThat(submission.encounterClass())
                .isEqualTo(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB"));
        assertThat(submission.diagnoses())
                .containsExactly(new Coding(DIAGNOSES, "DX-ASTHMA"), new Coding(DIAGNOSES, "DX-COPD"));
        assertThat(submission.items())
                .containsExactly(
                        new ClaimSubmission.LineItem(1, new Coding(PROCEDURES, "PROC-SPIRO"), 12_500),
                        new ClaimSubmission.LineItem(2, new Coding(PROCEDURES, "PROC-CONSULT"), 8_050));
        assertThat(submission.totalMinor()).isEqualTo(20_550);
        assertThat(submission.currency().getCurrencyCode()).isEqualTo("EUR");
    }

    private static Reference otherPatient(Bundle bundle) {
        String fullUrl = "urn:uuid:9d1b8f0e-3f0a-4a52-9c1e-0c8a4f1c0999";
        bundle.addEntry()
                .setFullUrl(fullUrl)
                .setResource(new Patient()
                        .addIdentifier(new Identifier()
                                .setSystem("https://ehr.example.org/mrn")
                                .setValue("MRN-0999")));
        return new Reference(fullUrl);
    }

    private static CodeableConcept verificationStatus(String code) {
        return new CodeableConcept(new org.hl7.fhir.r5.model.Coding(
                "http://terminology.hl7.org/CodeSystem/condition-ver-status", code, null));
    }

    @Nested
    class AcceptedVariants {

        @Test
        void unresolvableLogicalSubjectIsNotCompared() {
            ClaimSubmission submission = mapWith(b -> entry(b, Encounter.class)
                    .setSubject(new Reference().setIdentifier(new Identifier().setValue("MRN-0001"))));

            assertThat(submission.patientMrn()).isEqualTo("MRN-0001");
        }

        @ParameterizedTest
        @ValueSource(strings = {"confirmed", "provisional", "differential", "unconfirmed"})
        void diagnosesThatAreNotRefutedAreAccepted(String verification) {
            ClaimSubmission submission =
                    mapWith(b -> entry(b, Condition.class).setVerificationStatus(verificationStatus(verification)));

            assertThat(submission.diagnoses()).hasSize(2);
        }
    }

    @Nested
    class BusinessGapsAreLeftToTheScrubber {

        @Test
        void missingPatientMrnMapsToNull() {
            ClaimSubmission submission = mapWith(
                    b -> entry(b, Patient.class).getIdentifierFirstRep().setSystem("https://other.example.org/id"));

            assertThat(submission.patientMrn()).isNull();
        }

        @Test
        void claimWithoutPatientMapsToNullMrn() {
            assertThat(mapWith(b -> claimOf(b).setPatient(null)).patientMrn()).isNull();
        }

        @Test
        void missingPolicyNumberMapsToNull() {
            assertThat(mapWith(b -> entry(b, Coverage.class).getSubscriberId().clear())
                            .policyNumber())
                    .isNull();
        }

        @Test
        void noFocalCoverageAmongSeveralMapsToNullPayer() {
            ClaimSubmission submission = mapWith(b -> {
                claimOf(b).getInsuranceFirstRep().setFocal(false);
                claimOf(b).addInsurance().setSequence(2).setFocal(false).setCoverage(new Reference("Coverage/other"));
            });

            assertThat(submission.payerCode()).isNull();
            assertThat(submission.policyNumber()).isNull();
        }

        @Test
        void encounterWithoutClassMapsToNull() {
            assertThat(mapWith(b -> entry(b, Encounter.class).getClass_().clear())
                            .encounterClass())
                    .isNull();
        }
    }

    @Nested
    class R5ChoiceTypesAndReferences {

        @Test
        void payerFromReferencedOrganizationWhenInsurerIsLiteral() {
            ClaimSubmission submission = mapWith(b -> {
                Organization payer = new Organization();
                payer.addIdentifier().setValue("PAYER-FROM-ORG");
                b.addEntry().setFullUrl("urn:uuid:payer-org").setResource(payer);
                entry(b, Coverage.class).setInsurer(new Reference("urn:uuid:payer-org"));
            });

            assertThat(submission.payerCode()).isEqualTo("PAYER-FROM-ORG");
        }

        @Test
        void picksTheFocalCoverageAmongSeveral() {
            ClaimSubmission submission = mapWith(b -> {
                Coverage secondary = entry(b, Coverage.class).copy();
                secondary.getSubscriberIdFirstRep().setValue("SECONDARY9");
                b.addEntry().setFullUrl("urn:uuid:secondary").setResource(secondary);
                claimOf(b).getInsuranceFirstRep().setFocal(false);
                claimOf(b)
                        .addInsurance()
                        .setSequence(2)
                        .setFocal(true)
                        .setCoverage(new Reference("urn:uuid:secondary"));
            });

            assertThat(submission.policyNumber()).isEqualTo("SECONDARY9");
        }

        @Test
        void resolvesRelativeTypeIdReferences() {
            ClaimSubmission submission = mapWith(b -> {
                Bundle.BundleEntryComponent patientEntry = b.getEntry().get(1);
                patientEntry.setFullUrl("https://ehr.example.org/fhir/Patient/p1");
                patientEntry.getResource().setId("p1");
                claimOf(b).setPatient(new Reference("Patient/p1"));
                entry(b, Encounter.class).setSubject(new Reference("Patient/p1"));
                entry(b, Coverage.class).setBeneficiary(new Reference("Patient/p1"));
                entry(b, Condition.class).setSubject(new Reference("Patient/p1"));
            });

            assertThat(submission.patientMrn()).isEqualTo("MRN-0001");
        }

        @Test
        void diagnosesAreOrderedBySequence() {
            ClaimSubmission submission = mapWith(b -> {
                claimOf(b).getDiagnosis().get(0).setSequence(3);
            });

            assertThat(submission.diagnoses())
                    .containsExactly(new Coding(DIAGNOSES, "DX-COPD"), new Coding(DIAGNOSES, "DX-ASTHMA"));
        }

        @Test
        void dateOnlyEncounterStartIsAnchoredInTheClinicalZone() {
            ClaimSubmission submission = mapWith(
                    b -> entry(b, Encounter.class).getActualPeriod().setStartElement(new DateTimeType("2026-09-01")));

            // Europe/Dublin is UTC+1 (IST) on this date
            assertThat(submission.serviceStart()).isEqualTo(Instant.parse("2026-08-31T23:00:00Z"));
        }

        @Test
        void claimTotalIsOptional() {
            assertThat(mapWith(b -> claimOf(b).setTotal(null)).totalMinor()).isEqualTo(20_550);
        }
    }

    @Nested
    class StructuralProblemsAreRejected {

        @Test
        void batchBundles() {
            assertRejected(
                    b -> b.setType(Bundle.BundleType.BATCH), IssueType.NOTSUPPORTED, "Bundle.type", "collection");
        }

        @Test
        void claimNotFirst() {
            assertRejected(
                    b -> b.getEntry().addFirst(b.getEntry().remove(1)),
                    IssueType.STRUCTURE,
                    "Bundle.entry[0].resource",
                    "first Bundle entry");
        }

        @Test
        void preauthorizations() {
            assertRejected(
                    b -> claimOf(b).setUse(Enumerations.Use.PREAUTHORIZATION),
                    IssueType.NOTSUPPORTED,
                    "Claim.use",
                    "'claim'");
        }

        @ParameterizedTest
        @EnumSource(
                value = Enumerations.FinancialResourceStatusCodes.class,
                names = {"CANCELLED", "DRAFT", "ENTEREDINERROR"})
        void claimsThatAreNotActive(Enumerations.FinancialResourceStatusCodes status) {
            assertRejected(b -> claimOf(b).setStatus(status), IssueType.NOTSUPPORTED, "Claim.status", "'active'");
        }

        @Test
        void claimWithoutStatus() {
            assertRejected(b -> claimOf(b).setStatusElement(null), IssueType.NOTSUPPORTED, "Claim.status", "'active'");
        }

        @Test
        void encounterOfAnotherPatient() {
            assertRejected(
                    b -> entry(b, Encounter.class).setSubject(otherPatient(b)),
                    IssueType.INVARIANT,
                    "Encounter.subject",
                    "Claim.patient");
        }

        @Test
        void coverageOfAnotherPatient() {
            assertRejected(
                    b -> entry(b, Coverage.class).setBeneficiary(otherPatient(b)),
                    IssueType.INVARIANT,
                    "Coverage.beneficiary",
                    "Claim.patient");
        }

        @Test
        void diagnosisOfAnotherPatient() {
            assertRejected(
                    b -> entry(b, Condition.class).setSubject(otherPatient(b)),
                    IssueType.INVARIANT,
                    "Condition.subject",
                    "Claim.patient");
        }

        @Test
        void danglingEncounterSubject() {
            assertRejected(
                    b -> entry(b, Encounter.class).setSubject(new Reference("urn:uuid:missing")),
                    IssueType.NOTFOUND,
                    "Encounter.subject",
                    "urn:uuid:missing");
        }

        @ParameterizedTest
        @ValueSource(strings = {"refuted", "entered-in-error"})
        void diagnosisThatIsNotAValidFinding(String verification) {
            assertRejected(
                    b -> entry(b, Condition.class).setVerificationStatus(verificationStatus(verification)),
                    IssueType.BUSINESSRULE,
                    "Claim.diagnosis.where(sequence = 1)",
                    verification);
        }

        @Test
        void duplicateItemSequence() {
            assertRejected(
                    b -> claimOf(b).getItem().get(1).setSequence(1), IssueType.INVARIANT, "Claim.item", "sequence 1");
        }

        @Test
        void duplicateDiagnosisSequence() {
            assertRejected(
                    b -> claimOf(b).getDiagnosis().get(1).setSequence(1),
                    IssueType.INVARIANT,
                    "Claim.diagnosis",
                    "sequence 1");
        }

        @Test
        void missingClaimIdentifier() {
            assertRejected(
                    b -> claimOf(b).getIdentifierFirstRep().setSystem(null),
                    IssueType.REQUIRED,
                    "Claim.identifier",
                    "system and value");
        }

        @Test
        void danglingPatientReference() {
            assertRejected(
                    b -> claimOf(b).setPatient(new Reference("urn:uuid:missing")),
                    IssueType.NOTFOUND,
                    "Claim.patient",
                    "urn:uuid:missing");
        }

        @Test
        void referenceToTheWrongResourceType() {
            assertRejected(
                    b -> claimOf(b).setPatient(claimOf(b).getEncounterFirstRep()),
                    IssueType.NOTFOUND,
                    "Claim.patient",
                    "Patient");
        }

        @Test
        void danglingConditionReference() {
            assertRejected(
                    b -> claimOf(b).getDiagnosisFirstRep().setDiagnosis(new Reference("Condition/nope")),
                    IssueType.NOTFOUND,
                    "Claim.diagnosis.where(sequence = 1)",
                    "Condition/nope");
        }

        @Test
        void zeroEncounters() {
            assertRejected(b -> claimOf(b).getEncounter().clear(), IssueType.STRUCTURE, "Claim.encounter", "found 0");
        }

        @Test
        void twoEncounters() {
            assertRejected(
                    b -> claimOf(b).addEncounter(new Reference("urn:uuid:other")),
                    IssueType.STRUCTURE,
                    "Claim.encounter",
                    "found 2");
        }

        @Test
        void encounterStartWithoutOffset() {
            assertRejected(
                    b -> entry(b, Encounter.class)
                            .getActualPeriod()
                            .setStartElement(new DateTimeType("2026-09-01T09:30:00")),
                    IssueType.VALUE,
                    "Encounter.actualPeriod.start",
                    "UTC offset");
        }

        @Test
        void monthPrecisionEncounterStart() {
            assertRejected(
                    b -> entry(b, Encounter.class).getActualPeriod().setStartElement(new DateTimeType("2026-09")),
                    IssueType.VALUE,
                    "Encounter.actualPeriod.start",
                    "full date");
        }

        @Test
        void missingEncounterStart() {
            assertRejected(
                    b -> entry(b, Encounter.class).getActualPeriod().setStartElement(null),
                    IssueType.REQUIRED,
                    "Encounter.actualPeriod.start",
                    "required");
        }

        @Test
        void itemWithoutProcedureCode() {
            assertRejected(
                    b -> claimOf(b).getItemFirstRep().setProductOrService(new CodeableConcept()),
                    IssueType.REQUIRED,
                    "Claim.item.where(sequence = 1).productOrService",
                    "system and code");
        }

        @Test
        void diagnosisWithoutCode() {
            assertRejected(
                    b -> claimOf(b).getDiagnosis().get(1).setDiagnosis(new CodeableConcept()),
                    IssueType.REQUIRED,
                    "Claim.diagnosis.where(sequence = 2)",
                    "system and code");
        }

        @Test
        void itemWithoutNet() {
            assertRejected(
                    b -> claimOf(b).getItemFirstRep().setNet(null),
                    IssueType.REQUIRED,
                    "Claim.item.where(sequence = 1).net",
                    "value and currency");
        }

        @Test
        void netWithoutCurrency() {
            assertRejected(
                    b -> claimOf(b).getItemFirstRep().getNet().setCurrency(null),
                    IssueType.REQUIRED,
                    "Claim.item.where(sequence = 1).net",
                    "value and currency");
        }

        @Test
        void foreignCurrency() {
            assertRejected(
                    b -> claimOf(b).getItemFirstRep().getNet().setCurrency("USD"),
                    IssueType.VALUE,
                    "Claim.item.where(sequence = 1).net.currency",
                    "found USD");
        }

        @Test
        void fractionalCents() {
            assertRejected(
                    b -> claimOf(b).getItemFirstRep().getNet().setValue(new BigDecimal("125.005")),
                    IssueType.VALUE,
                    "Claim.item.where(sequence = 1).net",
                    "decimal places");
        }

        @Test
        void negativeAmount() {
            assertRejected(
                    b -> claimOf(b).getItemFirstRep().getNet().setValue(new BigDecimal("-1")),
                    IssueType.VALUE,
                    "Claim.item.where(sequence = 1).net",
                    "negative");
        }

        @Test
        void totalMismatch() {
            assertRejected(
                    b -> claimOf(b)
                            .setTotal(new Money()
                                    .setValue(new BigDecimal("205.51"))
                                    .setCurrency("EUR")),
                    IssueType.VALUE,
                    "Claim.total",
                    "205.51");
        }

        @Test
        void itemAmountsThatOverflowWhenSummed() {
            assertRejected(
                    b -> {
                        claimOf(b).setTotal(null);
                        claimOf(b).getItem().forEach(i -> i.getNet().setValue(new BigDecimal("90000000000000000")));
                    },
                    IssueType.VALUE,
                    "Claim.item",
                    "out of range");
        }

        @Test
        void tooManyItems() {
            assertRejected(
                    b -> {
                        claimOf(b).setTotal(null);
                        for (int i = 3; i <= 51; i++) {
                            claimOf(b)
                                    .addItem()
                                    .setSequence(i)
                                    .setProductOrService(
                                            claimOf(b).getItemFirstRep().getProductOrService())
                                    .setNet(new Money().setValue(BigDecimal.ONE).setCurrency("EUR"));
                        }
                    },
                    IssueType.TOOCOSTLY,
                    "Claim.item",
                    "at most 50");
        }

        @Test
        void noItems() {
            assertRejected(
                    b -> {
                        claimOf(b).getItem().clear();
                        claimOf(b).setTotal(null);
                    },
                    IssueType.INVALID,
                    "Claim",
                    "at least one item");
        }
    }

    @Test
    void identifierWithoutSystemIsSkippedInFavourOfACompleteOne() {
        ClaimSubmission submission =
                mapWith(b -> claimOf(b).getIdentifier().addFirst(new Identifier().setValue("no-system")));

        assertThat(submission.identifier().value()).isEqualTo("CLM-2026-0001");
    }
}

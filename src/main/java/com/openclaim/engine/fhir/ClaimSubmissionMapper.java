package com.openclaim.engine.fhir;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import com.openclaim.engine.claim.ClaimIdentifier;
import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.claim.Coding;
import com.openclaim.engine.config.OpenClaimProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Claim;
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
import org.hl7.fhir.r5.model.Resource;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Maps a {@code Claim/$submit} Bundle to the canonical {@link ClaimSubmission}.
 *
 * <p>Only problems that make the claim unidentifiable or unstorable are raised here as {@link FhirMappingException}
 * (HTTP 422, nothing persisted). Missing business data, such as a patient MRN or a policy number, is mapped to
 * {@code null} so that the scrubber rules record it as a violation on a persisted, rejected claim.
 */
@Component
public class ClaimSubmissionMapper {

    private final Currency currency;
    private final ZoneId clinicalZone;
    private final String mrnSystem;
    private final int maxItems;

    public ClaimSubmissionMapper(OpenClaimProperties properties) {
        this.currency = properties.currency();
        this.clinicalZone = properties.clinicalZone();
        this.mrnSystem = properties.fhir().mrnSystem();
        this.maxItems = properties.fhir().maxItems();
    }

    public ClaimSubmission map(Bundle bundle) {
        if (bundle.getType() != Bundle.BundleType.COLLECTION) {
            throw new FhirMappingException(
                    IssueType.NOTSUPPORTED,
                    "Bundle.type",
                    "Only a 'collection' Bundle holding one Claim and its referenced resources is supported");
        }
        if (!(bundle.getEntryFirstRep().getResource() instanceof Claim claim)) {
            throw new FhirMappingException(
                    IssueType.STRUCTURE, "Bundle.entry[0].resource", "The first Bundle entry must be the Claim");
        }
        if (claim.getUse() != Enumerations.Use.CLAIM) {
            throw new FhirMappingException(
                    IssueType.NOTSUPPORTED, "Claim.use", "Only Claim.use = 'claim' is supported");
        }
        BundleIndex index = new BundleIndex(bundle);
        Optional<Coverage> coverage = focalCoverage(claim, index);
        Encounter encounter = encounter(claim, index);
        List<ClaimSubmission.LineItem> items = items(claim);
        long total = total(claim, items);
        try {
            return new ClaimSubmission(
                    identifier(claim),
                    patientMrn(claim, index),
                    coverage.map(c -> payerCode(c, index)).orElse(null),
                    coverage.map(ClaimSubmissionMapper::policyNumber).orElse(null),
                    coverage.filter(Coverage::hasKind)
                            .map(c -> c.getKind().toCode())
                            .orElse(null),
                    serviceStart(encounter),
                    encounterClass(encounter),
                    diagnoses(claim, index),
                    items,
                    currency,
                    total);
        } catch (IllegalArgumentException e) {
            throw new FhirMappingException(IssueType.INVALID, "Claim", e.getMessage());
        }
    }

    private static ClaimIdentifier identifier(Claim claim) {
        return claim.getIdentifier().stream()
                .filter(id -> id.hasSystem() && id.hasValue())
                .findFirst()
                .map(id -> new ClaimIdentifier(id.getSystem(), id.getValue()))
                .orElseThrow(() -> new FhirMappingException(
                        IssueType.REQUIRED,
                        "Claim.identifier",
                        "Claim.identifier with both system and value is required to detect re-transmissions"));
    }

    private @Nullable String patientMrn(Claim claim, BundleIndex index) {
        if (!claim.hasPatient()) {
            return null;
        }
        Patient patient = resolve(index, claim.getPatient(), Patient.class, "Claim.patient");
        return patient.getIdentifier().stream()
                .filter(id -> mrnSystem.equals(id.getSystem()) && id.hasValue())
                .map(Identifier::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Optional<Coverage> focalCoverage(Claim claim, BundleIndex index) {
        List<Claim.InsuranceComponent> insurance = claim.getInsurance();
        for (int i = 0; i < insurance.size(); i++) {
            Claim.InsuranceComponent entry = insurance.get(i);
            if (entry.getFocal() || insurance.size() == 1) {
                return Optional.of(
                        resolve(index, entry.getCoverage(), Coverage.class, "Claim.insurance[" + i + "].coverage"));
            }
        }
        return Optional.empty();
    }

    /** Logical reference ({@code insurer.identifier}) first, else the identifier of a contained Organization. */
    private static @Nullable String payerCode(Coverage coverage, BundleIndex index) {
        Reference insurer = coverage.getInsurer();
        if (insurer.hasIdentifier() && insurer.getIdentifier().hasValue()) {
            return insurer.getIdentifier().getValue();
        }
        if (insurer.hasReference()) {
            Organization organization = resolve(index, insurer, Organization.class, "Coverage.insurer");
            return organization.getIdentifier().stream()
                    .filter(Identifier::hasValue)
                    .map(Identifier::getValue)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static @Nullable String policyNumber(Coverage coverage) {
        return coverage.getSubscriberId().stream()
                .filter(Identifier::hasValue)
                .map(Identifier::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Encounter encounter(Claim claim, BundleIndex index) {
        if (claim.getEncounter().size() != 1) {
            throw new FhirMappingException(
                    IssueType.STRUCTURE,
                    "Claim.encounter",
                    "Exactly one Claim.encounter is required; found "
                            + claim.getEncounter().size());
        }
        return resolve(index, claim.getEncounter().getFirst(), Encounter.class, "Claim.encounter[0]");
    }

    /**
     * The encounter start is the ledger's valid time, so it must identify an instant. A date is anchored at the start
     * of that day in the clinical zone; a time without an offset is rejected because HAPI would otherwise apply the
     * server's default zone.
     */
    private Instant serviceStart(Encounter encounter) {
        DateTimeType start = encounter.getActualPeriod().getStartElement();
        String expression = "Encounter.actualPeriod.start";
        if (start.isEmpty()) {
            throw new FhirMappingException(IssueType.REQUIRED, expression, "The encounter start is required");
        }
        TemporalPrecisionEnum precision = start.getPrecision();
        if (precision == TemporalPrecisionEnum.DAY) {
            return LocalDate.of(start.getYear(), start.getMonth() + 1, start.getDay())
                    .atStartOfDay(clinicalZone)
                    .toInstant();
        }
        if (precision.ordinal() < TemporalPrecisionEnum.DAY.ordinal()) {
            throw new FhirMappingException(
                    IssueType.VALUE, expression, "The encounter start must be at least a full date");
        }
        if (start.getTimeZone() == null) {
            throw new FhirMappingException(
                    IssueType.VALUE, expression, "An encounter start with a time must include a UTC offset");
        }
        return start.getValue().toInstant();
    }

    private static @Nullable Coding encounterClass(Encounter encounter) {
        return encounter.getClass_().stream()
                .flatMap(concept -> concept.getCoding().stream())
                .filter(coding -> coding.hasSystem() && coding.hasCode())
                .findFirst()
                .map(coding -> new Coding(coding.getSystem(), coding.getCode()))
                .orElse(null);
    }

    private static List<Coding> diagnoses(Claim claim, BundleIndex index) {
        List<Claim.DiagnosisComponent> sorted = claim.getDiagnosis().stream()
                .sorted(Comparator.comparingInt(Claim.DiagnosisComponent::getSequence))
                .toList();
        List<Coding> codes = new ArrayList<>();
        for (Claim.DiagnosisComponent diagnosis : sorted) {
            String expression = "Claim.diagnosis.where(sequence = %d)".formatted(diagnosis.getSequence());
            CodeableConcept concept;
            if (diagnosis.hasDiagnosisReference()) {
                concept = resolve(index, diagnosis.getDiagnosisReference(), Condition.class, expression)
                        .getCode();
            } else if (diagnosis.hasDiagnosisCodeableConcept()) {
                concept = diagnosis.getDiagnosisCodeableConcept();
            } else {
                throw new FhirMappingException(IssueType.REQUIRED, expression, "The diagnosis has no code");
            }
            codes.add(firstCoding(concept, expression));
        }
        return codes;
    }

    private List<ClaimSubmission.LineItem> items(Claim claim) {
        if (claim.getItem().size() > maxItems) {
            throw new FhirMappingException(
                    IssueType.TOOCOSTLY,
                    "Claim.item",
                    "A claim may have at most %d items; found %d"
                            .formatted(maxItems, claim.getItem().size()));
        }
        List<ClaimSubmission.LineItem> items = new ArrayList<>();
        for (Claim.ItemComponent item : claim.getItem()) {
            String expression = "Claim.item.where(sequence = %d)".formatted(item.getSequence());
            Coding procedure = firstCoding(item.getProductOrService(), expression + ".productOrService");
            long net = minorUnits(item.getNet(), expression + ".net");
            items.add(new ClaimSubmission.LineItem(item.getSequence(), procedure, net));
        }
        return items;
    }

    private long total(Claim claim, List<ClaimSubmission.LineItem> items) {
        long sum = 0;
        for (ClaimSubmission.LineItem item : items) {
            try {
                sum = Math.addExact(sum, item.netMinor());
            } catch (ArithmeticException e) {
                throw new FhirMappingException(IssueType.VALUE, "Claim.item", "Sum of item amounts is out of range");
            }
        }
        if (claim.hasTotal()) {
            long declared = minorUnits(claim.getTotal(), "Claim.total");
            if (declared != sum) {
                throw new FhirMappingException(
                        IssueType.VALUE,
                        "Claim.total",
                        "Claim.total %s does not equal the sum of item net amounts %s"
                                .formatted(
                                        MinorUnits.toDecimal(declared, currency), MinorUnits.toDecimal(sum, currency)));
            }
        }
        return sum;
    }

    private long minorUnits(Money money, String expression) {
        if (!money.hasValue() || !money.hasCurrency()) {
            throw new FhirMappingException(
                    IssueType.REQUIRED, expression, "An amount with value and currency is required");
        }
        if (!currency.getCurrencyCode().equals(money.getCurrency())) {
            throw new FhirMappingException(
                    IssueType.VALUE,
                    expression + ".currency",
                    "Only %s is accepted; found %s".formatted(currency.getCurrencyCode(), money.getCurrency()));
        }
        if (money.getValue().signum() < 0) {
            throw new FhirMappingException(IssueType.VALUE, expression, "Amounts must not be negative");
        }
        try {
            return MinorUnits.toMinor(money.getValue(), currency);
        } catch (IllegalArgumentException e) {
            throw new FhirMappingException(IssueType.VALUE, expression, e.getMessage());
        }
    }

    private static Coding firstCoding(CodeableConcept concept, String expression) {
        return concept.getCoding().stream()
                .filter(coding -> coding.hasSystem() && coding.hasCode())
                .findFirst()
                .map(coding -> new Coding(coding.getSystem(), coding.getCode()))
                .orElseThrow(() -> new FhirMappingException(
                        IssueType.REQUIRED, expression, "A coding with both system and code is required"));
    }

    private static <T extends Resource> T resolve(
            BundleIndex index, Reference reference, Class<T> type, String expression) {
        return index.resolve(reference, type)
                .orElseThrow(() -> new FhirMappingException(
                        IssueType.NOTFOUND,
                        expression,
                        "Reference '%s' does not resolve to a %s in this Bundle"
                                .formatted(reference.getReference(), type.getSimpleName())));
    }
}

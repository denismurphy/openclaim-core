package com.openclaim.engine.fhir;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Resource;

/**
 * Resolves literal references between the entries of one Bundle, by {@code fullUrl} (e.g. {@code urn:uuid:...}) or by
 * relative {@code Type/id}. HAPI also links references while parsing, but silently leaves dangling ones empty; this
 * index makes resolution explicit so a dangling reference can be reported.
 */
final class BundleIndex {

    private final Map<String, Resource> byReference = new HashMap<>();

    BundleIndex(Bundle bundle) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            if (resource == null) {
                continue;
            }
            if (entry.hasFullUrl()) {
                byReference.putIfAbsent(entry.getFullUrl(), resource);
            }
            if (resource.hasIdElement() && resource.getIdElement().hasIdPart()) {
                String relative =
                        resource.fhirType() + "/" + resource.getIdElement().getIdPart();
                byReference.putIfAbsent(relative, resource);
            }
        }
    }

    /**
     * @return the referenced resource, or empty if the reference has no literal value, or points outside the Bundle,
     *     or points at a resource of a different type
     */
    <T extends Resource> Optional<T> resolve(Reference reference, Class<T> type) {
        if (!reference.hasReference()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byReference.get(reference.getReference()))
                .filter(type::isInstance)
                .map(type::cast);
    }
}

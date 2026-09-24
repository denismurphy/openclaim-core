package com.openclaim.engine.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;

class FhirResourceHttpMessageConverterTest {

    private static final String BUNDLE_JSON = FhirFixtures.json(FhirFixtures.CLAIM_BUNDLE);

    private final FhirResourceHttpMessageConverter converter =
            new FhirResourceHttpMessageConverter(FhirFixtures.CONTEXT, 1024 * 1024);

    private static MockHttpInputMessage body(String json) {
        return body(json.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpInputMessage body(byte[] bytes) {
        MockHttpInputMessage message = new MockHttpInputMessage(bytes);
        message.getHeaders().setContentType(FhirMediaTypes.FHIR_JSON);
        return message;
    }

    @Test
    void supportsFhirResourcesOnly() {
        assertThat(converter.canRead(Bundle.class, FhirMediaTypes.FHIR_JSON)).isTrue();
        assertThat(converter.canRead(Bundle.class, MediaType.APPLICATION_JSON)).isTrue();
        assertThat(converter.canRead(Bundle.class, MediaType.APPLICATION_XML)).isFalse();
        assertThat(converter.canRead(String.class, FhirMediaTypes.FHIR_JSON)).isFalse();
        assertThat(converter.canWrite(OperationOutcome.class, FhirMediaTypes.FHIR_JSON))
                .isTrue();
    }

    @Test
    void readsABundle() throws IOException {
        Bundle bundle = (Bundle) converter.read(Bundle.class, body(BUNDLE_JSON));

        assertThat(bundle.getEntry()).hasSize(5);
    }

    @Test
    void rejectsUnknownElements() {
        String json = BUNDLE_JSON.replace("\"use\": \"claim\"", "\"use\": \"claim\", \"bogus\": 1");

        assertThatThrownBy(() -> converter.read(Bundle.class, body(json)))
                .isExactlyInstanceOf(HttpMessageNotReadableException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    void rejectsAResourceOfAnotherType() {
        assertThatThrownBy(() -> converter.read(Bundle.class, body("{\"resourceType\":\"Patient\"}")))
                .isExactlyInstanceOf(HttpMessageNotReadableException.class)
                .hasMessageContaining("expected \"Bundle\"");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> converter.read(Bundle.class, body("{not json")))
                .isExactlyInstanceOf(HttpMessageNotReadableException.class);
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] bytes = "{\"resourceType\":\"Patient\",\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        bytes[bytes.length - 3] = (byte) 0xC3; // lead byte of a 2-byte sequence followed by '"'

        assertThatThrownBy(() -> converter.read(Patient.class, body(bytes)))
                .isExactlyInstanceOf(HttpMessageNotReadableException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void acceptsABodyExactlyAtTheLimit() throws IOException {
        String json = "{\"resourceType\":\"Patient\"}";
        FhirResourceHttpMessageConverter tight =
                new FhirResourceHttpMessageConverter(FhirFixtures.CONTEXT, json.length());

        assertThat(tight.read(Patient.class, body(json))).isInstanceOf(Patient.class);
    }

    @Test
    void rejectsABodyOneByteOverTheLimit() {
        String json = "{\"resourceType\":\"Patient\"} ";
        FhirResourceHttpMessageConverter tight =
                new FhirResourceHttpMessageConverter(FhirFixtures.CONTEXT, json.length() - 1);

        assertThatThrownBy(() -> tight.read(Patient.class, body(json)))
                .isInstanceOf(FhirResourceHttpMessageConverter.PayloadTooLargeException.class);
    }

    @Test
    void writesFhirJson() throws IOException {
        MockHttpOutputMessage output = new MockHttpOutputMessage();

        converter.write(new Patient().setActive(true), null, output);

        assertThat(output.getHeaders().getContentType())
                .isEqualTo(new MediaType(FhirMediaTypes.FHIR_JSON, StandardCharsets.UTF_8));
        assertThat(output.getBodyAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"resourceType\":\"Patient\",\"active\":true}");
    }

    @Test
    void refusesANonsensicalLimit() {
        assertThatThrownBy(() -> new FhirResourceHttpMessageConverter(FhirFixtures.CONTEXT, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

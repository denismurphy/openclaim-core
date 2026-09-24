package com.openclaim.engine.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Reads and writes HAPI FHIR resources as {@code application/fhir+json} (plain {@code application/json} is also
 * read, as many clients send it).
 *
 * <p>Parsing is strict: unknown elements, invalid codes and a resource type other than the one the handler expects are
 * rejected as unreadable (HTTP 400). Bodies larger than {@code maxBodyBytes} are rejected before parsing, and bodies
 * that are not valid UTF-8 are rejected rather than decoded with replacement characters.
 */
public class FhirResourceHttpMessageConverter extends AbstractHttpMessageConverter<IBaseResource> {

    private final FhirContext fhirContext;
    private final long maxBodyBytes;

    public FhirResourceHttpMessageConverter(FhirContext fhirContext, long maxBodyBytes) {
        super(StandardCharsets.UTF_8, FhirMediaTypes.FHIR_JSON, MediaType.APPLICATION_JSON);
        if (maxBodyBytes < 1 || maxBodyBytes > Integer.MAX_VALUE - 1) {
            throw new IllegalArgumentException(
                    "maxBodyBytes must be between 1 and %d".formatted(Integer.MAX_VALUE - 1));
        }
        this.fhirContext = fhirContext;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return IBaseResource.class.isAssignableFrom(clazz);
    }

    @Override
    protected IBaseResource readInternal(Class<? extends IBaseResource> clazz, HttpInputMessage inputMessage)
            throws IOException {
        byte[] body = inputMessage.getBody().readNBytes((int) maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            throw new PayloadTooLargeException(maxBodyBytes, inputMessage);
        }
        String json;
        try {
            json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new HttpMessageNotReadableException("Request body is not valid UTF-8", e, inputMessage);
        }
        try {
            return newParser().parseResource(clazz, json);
        } catch (DataFormatException e) {
            throw new HttpMessageNotReadableException(e.getMessage(), e, inputMessage);
        }
    }

    @Override
    protected void writeInternal(IBaseResource resource, HttpOutputMessage outputMessage) throws IOException {
        Writer writer = new OutputStreamWriter(outputMessage.getBody(), StandardCharsets.UTF_8);
        newParser().encodeResourceToWriter(resource, writer);
        writer.flush();
    }

    @Override
    protected MediaType getDefaultContentType(IBaseResource resource) {
        return FhirMediaTypes.FHIR_JSON;
    }

    private IParser newParser() {
        return fhirContext.newJsonParser().setParserErrorHandler(new StrictErrorHandler());
    }

    /** The request body exceeds the configured limit; rendered as HTTP 413. */
    public static class PayloadTooLargeException extends HttpMessageNotReadableException {

        public PayloadTooLargeException(long limit, HttpInputMessage inputMessage) {
            super("Request body exceeds the %d byte limit".formatted(limit), inputMessage);
        }
    }
}

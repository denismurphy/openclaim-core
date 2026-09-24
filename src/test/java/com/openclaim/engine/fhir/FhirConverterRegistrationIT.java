package com.openclaim.engine.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FhirConverterRegistrationIT {

    @Autowired
    RequestMappingHandlerAdapter handlerAdapter;

    @Test
    void mvcUsesTheFhirConverterAheadOfGenericJsonConverters() {
        List<HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();

        int fhir = indexOf(converters, FhirResourceHttpMessageConverter.class);
        assertThat(fhir).as("FHIR converter registered").isNotNegative();
        converters.stream()
                .filter(c -> c.getClass().getName().contains("Jackson"))
                .forEach(json -> assertThat(converters.indexOf(json))
                        .as(
                                "%s must come after the FHIR converter",
                                json.getClass().getSimpleName())
                        .isGreaterThan(fhir));
    }

    @Test
    void noJackson2ConverterSneaksInViaHapi() {
        assertThat(handlerAdapter.getMessageConverters())
                .noneMatch(c ->
                        c.getClass().getName().startsWith("org.springframework.http.converter.json.MappingJackson2"));
    }

    private static int indexOf(List<HttpMessageConverter<?>> converters, Class<?> type) {
        for (int i = 0; i < converters.size(); i++) {
            if (type.isInstance(converters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}

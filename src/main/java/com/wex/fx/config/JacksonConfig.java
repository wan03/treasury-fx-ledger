package com.wex.fx.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Renders every {@link BigDecimal} on the wire as a JSON <em>string</em> (api-contract.md / OpenAPI:
 * {@code amount}, {@code exchangeRate}, {@code convertedAmount} are all {@code type: string}). This is
 * the money-precision contract from the read side: a JSON number invites a consumer to parse it into a
 * binary {@code double} and lose cents, so we hand it over as text and let them choose an exact type
 * (D-04). The request side is already string-typed at the DTO.
 *
 * <p>Why a type-level serializer rather than field annotations: the response DTOs live in
 * {@code application.dto}, which ArchUnit proves free of any framework import — so they cannot carry a
 * Jackson {@code @JsonSerialize}. Centralizing the rule here keeps that fence intact and applies it
 * uniformly. It is deliberately scoped to {@code BigDecimal} (not a blanket numbers-as-strings), so
 * genuine integers — notably the RFC 9457 {@code status} — stay JSON numbers as their schemas require.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer bigDecimalAsString() {
        return builder -> builder.serializerByType(BigDecimal.class, ToStringSerializer.instance);
    }
}

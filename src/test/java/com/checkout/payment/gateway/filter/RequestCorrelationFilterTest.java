package com.checkout.payment.gateway.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationFilterTest {

    private static final String HEADER = "X-Correlation-ID";

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void noHeaderProvided_generatesCorrelationIdAndSetsResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String correlationId = response.getHeader(HEADER);
        assertThat(correlationId).isNotBlank();
    }

    @Test
    void blankHeaderProvided_generatesNewCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments");
        request.addHeader(HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(HEADER)).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    void headerProvided_reusesGivenCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments");
        request.addHeader(HEADER, "my-correlation-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(HEADER)).isEqualTo("my-correlation-id");
    }

    @Test
    void duringChainExecution_mdcContainsCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments");
        request.addHeader(HEADER, "chain-check-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.get("correlationId")));

        assertThat(mdcDuringChain.get()).isEqualTo("chain-check-id");
    }

    @Test
    void afterFilterCompletes_mdcIsCleared() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void chainThrows_mdcStillClearedByFinally() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { throw new IllegalStateException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
            .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("correlationId")).isNull();
    }
}

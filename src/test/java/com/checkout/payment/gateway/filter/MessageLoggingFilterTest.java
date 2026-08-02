package com.checkout.payment.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MessageLoggingFilterTest {

    private final MessageLoggingFilter filter = new MessageLoggingFilter();

    @Test
    void actuatorUri_shouldNotFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void swaggerUri_shouldNotFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void apiDocsUri_shouldNotFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void paymentsUri_shouldFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void doFilterInternal_passesThroughChainAndPreservesResponseBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        request.setContent("{\"currency\":\"GBP\"}".getBytes());
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            req.getInputStream().readAllBytes();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
            res.getOutputStream().write("{\"status\":\"Authorized\"}".getBytes());
        });

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"Authorized\"}");
    }

    @Test
    void doFilterInternal_emptyRequestAndResponseBodies_doesNotThrow() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments/123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void doFilterInternal_chainThrows_exceptionPropagatesAndBodyStillLogged() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("downstream failure");
            })
        ).isInstanceOf(IllegalStateException.class);
    }
}

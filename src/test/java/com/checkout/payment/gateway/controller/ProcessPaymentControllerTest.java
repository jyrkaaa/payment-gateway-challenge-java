package com.checkout.payment.gateway.controller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "gateway.acquiring-bank.resilience.retry.max-attempts=1")
class ProcessPaymentControllerTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @DynamicPropertySource
    static void overrideBankUrl(DynamicPropertyRegistry registry) {
        registry.add("gateway.acquiring-bank.url",
            () -> "http://localhost:" + wireMock.port() + "/payments");
    }

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @Test
    void processPayment_authorized_returns201WithAuthorizedStatus() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"abc-123\"}")));

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("Authorized"))
            .andExpect(jsonPath("$.card_number_last_four").value("8877"))
            .andExpect(jsonPath("$.currency").value("GBP"))
            .andExpect(jsonPath("$.amount").value(100))
            .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void processPayment_declined_returns201WithDeclinedStatus() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":false,\"authorization_code\":\"\"}")));

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248872","expiry_month":4,"expiry_year":2030,
                     "currency":"USD","amount":50,"cvv":"321"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("Declined"));
    }

    @Test
    void processPayment_bankReturns503_returns502() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/payments"))
            .willReturn(serviceUnavailable()));

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248870","expiry_month":4,"expiry_year":2030,
                     "currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isBadGateway());
    }

    @Test
    void processPayment_missingCardNumber_returns422_noBankCall() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expiry_month":4,"expiry_year":2030,"currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isUnprocessableContent());

        wireMock.verify(0, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void processPayment_unsupportedCurrency_returns422() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"CAD","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    void processPayment_expiredCard_returns422() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":1,"expiry_year":2020,
                     "currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    void processPayment_floatAmount_returns422_noBankCall() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"GBP","amount":105.4,"cvv":"123"}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.message").value("amount must be a whole number"));

        wireMock.verify(0, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void processPayment_invalidCvv_returns422() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"GBP","amount":100,"cvv":"ab"}
                    """))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    void processPayment_withIdempotencyKey_firstRequest_returns201NoReplayHeader() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"abc-123\"}")));

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .header("Idempotency-Key", "idem-key-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("Authorized"))
            .andExpect(result -> assertThat(
                result.getResponse().getHeader("Idempotency-Key")).isEqualTo("idem-key-001"))
            .andExpect(result -> assertThat(
                result.getResponse().getHeader("Idempotent-Replayed")).isNull());
    }

    @Test
    void processPayment_withIdempotencyKey_duplicateRequest_returns200WithReplayedHeader() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"abc-123\"}")));

        String body = """
            {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
             "currency":"GBP","amount":100,"cvv":"123"}
            """;

        String firstResponse = mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .header("Idempotency-Key", "idem-key-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String originalId = firstResponse.split("\"id\":\"")[1].split("\"")[0];

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .header("Idempotency-Key", "idem-key-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(originalId))
            .andExpect(jsonPath("$.status").value("Authorized"))
            .andExpect(result -> assertThat(
                result.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true"))
            .andExpect(result -> assertThat(
                result.getResponse().getHeader("Idempotency-Key")).isEqualTo("idem-key-002"));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void processPayment_withoutIdempotencyKey_noIdempotencyHeadersInResponse() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"abc-123\"}")));

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(result -> assertThat(
                result.getResponse().getHeader("Idempotency-Key")).isNull())
            .andExpect(result -> assertThat(
                result.getResponse().getHeader("Idempotent-Replayed")).isNull());
    }

    @Test
    void getPayment_afterSuccessfulPost_returns200WithMatchingData() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"abc-123\"}")));

        String postBody = mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"EUR","amount":200,"cvv":"999"}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String id = postBody.split("\"id\":\"")[1].split("\"")[0];

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/payments/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.status").value("Authorized"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.amount").value(200));
    }
}

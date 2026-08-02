package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.exception.BankServiceUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpBankClientTest {

    private static WireMockServer wireMock;
    private HttpBankClient client;

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
    void setUp() {
        wireMock.resetAll();
        client = new HttpBankClient(restClientFor("http://localhost:" + wireMock.port() + "/payments"));
    }

    private static RestClient restClientFor(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private BankPaymentRequest request() {
        return BankPaymentRequest.builder()
            .cardNumber("2222405343248877")
            .expiryDate("04/2030")
            .currency("GBP")
            .amount(10000)
            .cvv("123")
            .build();
    }

    @Test
    void sendPayment_authorized_returnsResponseWithStatusCode() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"abc-123\"}")));

        BankPaymentResponse response = client.sendPayment(request());

        assertThat(response.isAuthorized()).isTrue();
        assertThat(response.getAuthorizationCode()).isEqualTo("abc-123");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void sendPayment_bankReturns503_throwsBankServiceUnavailableException() {
        wireMock.stubFor(post(urlEqualTo("/payments")).willReturn(serviceUnavailable()));

        assertThatThrownBy(() -> client.sendPayment(request()))
            .isInstanceOf(BankServiceUnavailableException.class)
            .hasMessageContaining("503");
    }

    @Test
    void sendPayment_bankReturns4xx_throwsBankServiceUnavailableException() {
        wireMock.stubFor(post(urlEqualTo("/payments")).willReturn(badRequest()));

        assertThatThrownBy(() -> client.sendPayment(request()))
            .isInstanceOf(BankServiceUnavailableException.class)
            .hasMessageContaining("400");
    }

    @Test
    void sendPayment_emptyResponseBody_throwsBankCommunicationException() {
        wireMock.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.sendPayment(request()))
            .isInstanceOf(BankCommunicationException.class)
            .hasMessageContaining("null response");
    }

    @Test
    void sendPayment_bankUnreachable_throwsBankServiceUnavailableExceptionFromNetworkError() {
        HttpBankClient unreachableClient = new HttpBankClient(restClientFor("http://localhost:1/payments"));

        assertThatThrownBy(() -> unreachableClient.sendPayment(request()))
            .isInstanceOf(BankServiceUnavailableException.class)
            .hasMessageContaining("unreachable");
    }
}

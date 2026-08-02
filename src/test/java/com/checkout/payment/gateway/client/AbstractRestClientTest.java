package com.checkout.payment.gateway.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractRestClientTest {

    private static WireMockServer wireMock;
    private TestClient client;

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
        client = new TestClient(RestClient.builder()
            .baseUrl("http://localhost:" + wireMock.port() + "/status")
            .build());
    }

    @Test
    void get_success_returnsBodyWithStatusCode() {
        wireMock.stubFor(get(urlEqualTo("/status"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"code-1\"}")));

        BankPaymentResponse response = client.doGet();

        assertThat(response.isAuthorized()).isTrue();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void get_httpError_delegatesToMapHttpError() {
        wireMock.stubFor(get(urlEqualTo("/status")).willReturn(notFound()));

        assertThatThrownBy(() -> client.doGet())
            .isInstanceOf(TestClient.MappedException.class)
            .hasMessageContaining("404");
    }

    @Test
    void get_emptyResponseBody_returnsNullBody() {
        wireMock.stubFor(get(urlEqualTo("/status")).willReturn(aResponse().withStatus(200)));

        assertThat(client.doGet()).isNull();
    }

    @Test
    void get_networkUnreachable_delegatesToMapNetworkError() {
        TestClient unreachableClient = new TestClient(RestClient.builder()
            .baseUrl("http://localhost:1/status")
            .build());

        assertThatThrownBy(unreachableClient::doGet)
            .isInstanceOf(TestClient.MappedException.class)
            .hasMessageContaining("network-error");
    }

    @Test
    void toJson_nullObject_returnsNull() {
        assertThat(client.toJsonPublic(null)).isNull();
    }

    @Test
    void toJson_object_returnsJsonString() {
        BankPaymentRequest request = BankPaymentRequest.builder().currency("GBP").amount(100).build();

        assertThat(client.toJsonPublic(request)).contains("\"currency\":\"GBP\"");
    }

    @Test
    void toJson_unserializableObject_returnsNull() {
        assertThat(client.toJsonPublic(new EmptyBean())).isNull();
    }

    private static class EmptyBean {
    }

    private static class TestClient extends AbstractRestClient {

        TestClient(RestClient restClient) {
            super(restClient);
        }

        @Override
        protected String TargetServiceName() {
            return "test-service";
        }

        @Override
        protected RuntimeException mapHttpError(HttpStatusCode statusCode, String responseBody) {
            return new MappedException("http-error " + statusCode.value());
        }

        @Override
        protected RuntimeException mapNetworkError(ResourceAccessException ex) {
            return new MappedException("network-error");
        }

        BankPaymentResponse doGet() {
            return get("/status", BankPaymentResponse.class);
        }

        String toJsonPublic(Object obj) {
            return toJson(obj);
        }

        static class MappedException extends RuntimeException {
            MappedException(String message) {
                super(message);
            }
        }
    }
}

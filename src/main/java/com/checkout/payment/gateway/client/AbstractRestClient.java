package com.checkout.payment.gateway.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public abstract class AbstractRestClient extends AbstractApiClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    protected final RestClient restClient;

    protected AbstractRestClient(RestClient restClient) {
        this.restClient = restClient;
    }

    protected abstract RuntimeException mapHttpError(int statusCode, RestClientResponseException ex);

    protected abstract RuntimeException mapNetworkError(ResourceAccessException ex);

    protected <T> T post(String endpoint, Object requestBody, Class<T> responseType) {
        return executeWithLogging("POST", endpoint, toJson(requestBody), () -> {
            try {
                T response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(responseType);
                return new CallResult<>(response, 200, toJson(response));
            } catch (RestClientResponseException ex) {
                throw new CallException(ex.getStatusCode().value(), ex.getResponseBodyAsString(),
                    mapHttpError(ex.getStatusCode().value(), ex));
            } catch (ResourceAccessException ex) {
                throw new CallException(null, null, mapNetworkError(ex));
            }
        });
    }

    protected <T> T get(String endpoint, Class<T> responseType) {
        return executeWithLogging("GET", endpoint, null, () -> {
            try {
                T response = restClient.get()
                    .retrieve()
                    .body(responseType);
                return new CallResult<>(response, 200, toJson(response));
            } catch (RestClientResponseException ex) {
                throw new CallException(ex.getStatusCode().value(), ex.getResponseBodyAsString(),
                    mapHttpError(ex.getStatusCode().value(), ex));
            } catch (ResourceAccessException ex) {
                throw new CallException(null, null, mapNetworkError(ex));
            }
        });
    }

    protected String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}

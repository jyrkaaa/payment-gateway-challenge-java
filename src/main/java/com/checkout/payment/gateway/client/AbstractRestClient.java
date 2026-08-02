package com.checkout.payment.gateway.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public abstract class AbstractRestClient extends AbstractApiClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    protected final RestClient restClient;

    protected AbstractRestClient(RestClient restClient) {
        this.restClient = restClient;
    }

    protected abstract RuntimeException mapHttpError(HttpStatusCode statusCode, String responseBody);

    protected abstract RuntimeException mapNetworkError(ResourceAccessException ex);

    protected <T extends RestApiResponse> T post(String endpoint, Object requestBody, Class<T> responseType) {
        return executeWithLogging("POST", endpoint, toJson(requestBody), () -> {
            try {
                ResponseEntity<T> entity = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(responseType);
                T body = entity.getBody();
                if (body != null) body.setStatusCode(entity.getStatusCode());
                return new CallResult<>(body, entity.getStatusCode(), toJson(body));
            } catch (RestClientResponseException ex) {
                HttpStatusCode status = ex.getStatusCode();
                throw new CallException(status, ex.getResponseBodyAsString(),
                    mapHttpError(status, ex.getResponseBodyAsString()));
            } catch (ResourceAccessException ex) {
                throw new CallException(null, null, mapNetworkError(ex));
            }
        });
    }

    protected <T extends RestApiResponse> T get(String endpoint, Class<T> responseType) {
        return executeWithLogging("GET", endpoint, null, () -> {
            try {
                ResponseEntity<T> entity = restClient.get()
                    .retrieve()
                    .toEntity(responseType);
                T body = entity.getBody();
                if (body != null) body.setStatusCode(entity.getStatusCode());
                return new CallResult<>(body, entity.getStatusCode(), toJson(body));
            } catch (RestClientResponseException ex) {
                HttpStatusCode status = ex.getStatusCode();
                throw new CallException(status, ex.getResponseBodyAsString(),
                    mapHttpError(status, ex.getResponseBodyAsString()));
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

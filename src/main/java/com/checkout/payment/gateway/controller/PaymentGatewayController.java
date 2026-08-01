package com.checkout.payment.gateway.controller;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.model.ProcessedPayment;
import com.checkout.payment.gateway.service.IPaymentGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping(PaymentGatewayController.BASE_PATH)
@Tag(name = "Payments", description = "Payment processing and retrieval")
public class PaymentGatewayController {

    public static final String API_VERSION = "v1";
    public static final String BASE_PATH = "/api/" + API_VERSION + "/payments";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final IPaymentGatewayService paymentGatewayService;

    @Operation(
        summary = "Process a payment",
        description = "Submits a card payment to the acquiring bank. " +
                      "Provide an optional `Idempotency-Key` header to safely retry without double-charging. " +
                      "A duplicate request with the same key returns the original response with `Idempotent-Replayed: true`."
    )
    @ApiResponse(responseCode = "201", description = "Payment processed (Authorized or Declined)")
    @ApiResponse(responseCode = "422", description = "Payment rejected — invalid input, bank was not called")
    @ApiResponse(responseCode = "502", description = "Acquiring bank unavailable")
    @PostMapping
    public ResponseEntity<PostPaymentResponse> processPayment(
            @Valid @RequestBody PostPaymentRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
            @Size(min = 1, max = 255, message = "Idempotency-Key must be 1-255 characters")
            @Pattern(regexp = "^[A-Za-z0-9_\\-]+$",
                message = "Idempotency-Key must contain only letters, digits, '-' or '_'")
            String idempotencyKey)
        {

        ProcessedPayment result = paymentGatewayService.processPayment(request, Optional.ofNullable(idempotencyKey));

        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null) {
            headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        if (result.replayed()) {
            headers.set(IDEMPOTENT_REPLAYED_HEADER, "true");
        }

        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).headers(headers).body(result.response());
    }

    @Operation(summary = "Retrieve a payment by ID")
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @GetMapping("/{id}")
    public ResponseEntity<PostPaymentResponse> getPaymentById(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentGatewayService.getPaymentById(id));
    }
}

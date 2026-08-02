package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PaymentGatewayControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired PaymentsRepository paymentsRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void whenPaymentWithIdExistThenCorrectPaymentIsReturned() throws Exception {
        PostPaymentResponse payment = new PostPaymentResponse();
        payment.setId(UUID.randomUUID());
        payment.setAmount(1000);
        payment.setCurrency("USD");
        payment.setStatus(PaymentStatus.AUTHORIZED);
        payment.setExpiryMonth(12);
        payment.setExpiryYear(2024);
        payment.setCardNumberLastFour(4321);

        paymentsRepository.add(payment);

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/payments/" + payment.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(payment.getStatus().getName()))
            .andExpect(jsonPath("$.card_number_last_four").value(payment.getCardNumberLastFour()))
            .andExpect(jsonPath("$.expiry_month").value(payment.getExpiryMonth()))
            .andExpect(jsonPath("$.expiry_year").value(payment.getExpiryYear()))
            .andExpect(jsonPath("$.currency").value(payment.getCurrency()))
            .andExpect(jsonPath("$.amount").value(payment.getAmount()));
    }

    @Test
    void whenPaymentWithIdDoesNotExistThen404IsReturned() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/payments/" + UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Payment not found"));
    }
}

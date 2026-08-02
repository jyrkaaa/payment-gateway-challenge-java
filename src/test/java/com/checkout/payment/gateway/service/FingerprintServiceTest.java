package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintServiceTest {

    @Test
    void sameRequest_returnsSameFingerprint() {
        PostPaymentRequest a = request("2222405343248877", 4, 2030, "GBP", 10000, "123");
        PostPaymentRequest b = request("2222405343248877", 4, 2030, "GBP", 10000, "123");

        assertThat(FingerprintService.fingerprint(a)).isEqualTo(FingerprintService.fingerprint(b));
    }

    @Test
    void differentAmount_returnsDifferentFingerprint() {
        PostPaymentRequest a = request("2222405343248877", 4, 2030, "GBP", 10000, "123");
        PostPaymentRequest b = request("2222405343248877", 4, 2030, "GBP", 99900, "123");

        assertThat(FingerprintService.fingerprint(a)).isNotEqualTo(FingerprintService.fingerprint(b));
    }

    @Test
    void differentCardNumber_returnsDifferentFingerprint() {
        PostPaymentRequest a = request("2222405343248877", 4, 2030, "GBP", 10000, "123");
        PostPaymentRequest b = request("2222405343248872", 4, 2030, "GBP", 10000, "123");

        assertThat(FingerprintService.fingerprint(a)).isNotEqualTo(FingerprintService.fingerprint(b));
    }

    @Test
    void differentCurrency_returnsDifferentFingerprint() {
        PostPaymentRequest a = request("2222405343248877", 4, 2030, "GBP", 10000, "123");
        PostPaymentRequest b = request("2222405343248877", 4, 2030, "USD", 10000, "123");

        assertThat(FingerprintService.fingerprint(a)).isNotEqualTo(FingerprintService.fingerprint(b));
    }

    @Test
    void differentCvv_returnsDifferentFingerprint() {
        PostPaymentRequest a = request("2222405343248877", 4, 2030, "GBP", 10000, "123");
        PostPaymentRequest b = request("2222405343248877", 4, 2030, "GBP", 10000, "456");

        assertThat(FingerprintService.fingerprint(a)).isNotEqualTo(FingerprintService.fingerprint(b));
    }

    @Test
    void returnsLowerHexSha256() {
        PostPaymentRequest r = request("2222405343248877", 4, 2030, "GBP", 10000, "123");
        String fp = FingerprintService.fingerprint(r);

        assertThat(fp).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void nullCardNumber_doesNotThrow() {
        PostPaymentRequest r = request(null, 4, 2030, "GBP", 10000, "123");
        assertThat(FingerprintService.fingerprint(r)).hasSize(64);
    }

    private PostPaymentRequest request(String cardNumber, int month, int year, String currency, int amount, String cvv) {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setCardNumber(cardNumber);
        r.setExpiryMonth(month);
        r.setExpiryYear(year);
        r.setCurrency(currency);
        r.setAmount(amount);
        r.setCvv(cvv);
        return r;
    }
}

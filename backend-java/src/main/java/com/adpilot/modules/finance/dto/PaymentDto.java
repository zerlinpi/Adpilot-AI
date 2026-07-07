package com.adpilot.modules.finance.dto;

import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Typed request body for {@code POST /api/finance/payments}.
 *
 * <p>This replaces the previously untyped {@code Object} body so financial payment input is
 * strongly typed and validated at the boundary, mirroring the {@link InvoiceDto} style used by the
 * sibling {@code createInvoice} endpoint.</p>
 *
 * <p><b>Conservative validation.</b> The payment service resolves several fields conditionally —
 * {@code storeId} is only required when {@code invoiceId} is absent (otherwise it is derived from
 * the invoice), and {@code amount} defaults to the invoice total when omitted — so those fields are
 * intentionally NOT marked required here to preserve the exact behavior of valid requests. The one
 * always-true constraint the service already enforces is that a supplied {@code amount} must be
 * positive; expressing it as {@link Positive} (which permits {@code null}) rejects only input the
 * service already rejects, via the project's standard validation error.</p>
 */
@Data
public class PaymentDto {

    /** Store the payment belongs to. Optional: derived from the invoice when {@code invoiceId} is given. */
    private String storeId;

    /** Invoice this payment settles. Optional: a standalone payment supplies {@code storeId} instead. */
    private String invoiceId;

    private String paymentNumber;
    private String paymentMethod;

    /** Payment date in {@code yyyy-MM-dd} form; defaults to today when omitted. */
    private String paymentDate;

    /** Payment amount; when supplied it must be positive. Defaults to the invoice total when omitted. */
    @Positive(message = "amount must be positive")
    private BigDecimal amount;

    private String currency;
    private String status;
    private String referenceNumber;
    private String notes;
}

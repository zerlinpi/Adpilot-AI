package com.adpilot.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CustomerReviewDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    private String reviewId;
    private String asin;
    private String sku;
    private String reviewerName;
    private Integer rating;
    private String title;
    private String reviewText;
    private String reviewDate;
    private Boolean verifiedPurchase;
    private Integer helpfulVotes;
    private String sentiment;
    private BigDecimal sentimentScore;
}

package com.adpilot.modules.review.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomerReviewVo {

    private String id;
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
    private Double sentimentScore;
    private String status;
    private String responseText;
    private String respondedAt;
    private String createdAt;
}

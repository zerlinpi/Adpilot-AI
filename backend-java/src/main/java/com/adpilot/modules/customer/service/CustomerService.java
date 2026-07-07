package com.adpilot.modules.customer.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.customer.vo.BuyerMessageVo;

public interface CustomerService {

    /**
     * List buyer messages with pagination.
     */
    PageResponse<BuyerMessageVo> listBuyerMessages(String storeId, int page, int pageSize);

    /**
     * Get buyer message by ID.
     */
    BuyerMessageVo getBuyerMessageById(String id);

    /**
     * Reply to a buyer message.
     */
    BuyerMessageVo replyToBuyerMessage(String id, String reply);

    /**
     * List customer tickets with pagination.
     */
    PageResponse<?> listCustomerTickets(String storeId, int page, int pageSize);

    /**
     * Create a customer ticket.
     */
    Object createCustomerTicket(Object dto);

    /**
     * Convert a buyer message into a Customer_Ticket, associating the ticket with
     * the originating Store and inheriting that Store's Store_Group
     * (platform-workspace-rbac Req 9.2). The resulting ticket's
     * {@code store_group_id} equals the originating Store's {@code store_group_id}.
     *
     * @param messageId the buyer message to convert
     * @return the created ticket view
     */
    Object convertMessageToTicket(String messageId);

    /**
     * Generate a draft reply for a customer ticket.
     */
    Object generateReplyDraft(String ticketId);
}

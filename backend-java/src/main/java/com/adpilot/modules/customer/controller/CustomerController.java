package com.adpilot.modules.customer.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.customer.dto.TicketAiConfirmCommand;
import com.adpilot.modules.customer.service.CustomerService;
import com.adpilot.modules.customer.service.TicketAiAssistantService;
import com.adpilot.modules.customer.vo.BuyerMessageVo;
import com.adpilot.modules.customer.vo.TicketAiProposalVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final TicketAiAssistantService ticketAiAssistantService;

    /**
     * GET /api/buyer-messages - List buyer messages with pagination.
     */
    @GetMapping("/buyer-messages")
    public ApiResponse<PageResponse<BuyerMessageVo>> listBuyerMessages(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<BuyerMessageVo> result = customerService.listBuyerMessages(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/buyer-messages/{id} - Get buyer message by ID.
     */
    @GetMapping("/buyer-messages/{id}")
    public ApiResponse<BuyerMessageVo> getBuyerMessage(@PathVariable String id) {
        BuyerMessageVo message = customerService.getBuyerMessageById(id);
        return ApiResponse.ok(message);
    }

    /**
     * POST /api/buyer-messages/{id}/reply - Reply to a buyer message.
     */
    @PostMapping("/buyer-messages/{id}/reply")
    @RequirePermission("customer:manage")
    public ApiResponse<BuyerMessageVo> replyToBuyerMessage(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String reply = body.get("reply");
        BuyerMessageVo message = customerService.replyToBuyerMessage(id, reply);
        return ApiResponse.ok(message);
    }

    /**
     * GET /api/customer-tickets - List customer tickets with pagination.
     */
    @GetMapping("/customer-tickets")
    public ApiResponse<PageResponse<?>> listCustomerTickets(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<?> result = customerService.listCustomerTickets(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/customer-tickets - Create a customer ticket.
     */
    @PostMapping("/customer-tickets")
    @RequirePermission("customer:manage")
    public ApiResponse<?> createCustomerTicket(@RequestBody Object dto) {
        Object result = customerService.createCustomerTicket(dto);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/customer-tickets/{id}/reply-draft - Generate a draft reply for a ticket.
     */
    @PostMapping("/customer-tickets/{id}/reply-draft")
    @RequirePermission("customer:manage")
    public ApiResponse<?> generateReplyDraft(@PathVariable String id) {
        Object result = customerService.generateReplyDraft(id);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/customer-tickets/{id}/ai-assist - Produce AI proposals (draft reply,
     * suggested classification, suggested action) for a ticket. The proposals are not
     * applied and no reply is sent until the operator explicitly confirms
     * (platform-workspace-rbac Req 9.3, 9.4).
     */
    @PostMapping("/customer-tickets/{id}/ai-assist")
    @RequirePermission("customer:manage")
    public ApiResponse<TicketAiProposalVo> assistTicket(@PathVariable String id) {
        TicketAiProposalVo result = ticketAiAssistantService.assist(id);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/customer-tickets/{id}/ai-confirm - Apply an operator-confirmed AI reply
     * or action and record the AI provenance in the audit trail (Req 9.5).
     */
    @PostMapping("/customer-tickets/{id}/ai-confirm")
    @RequirePermission("customer:manage")
    public ApiResponse<?> confirmTicketAi(
            @PathVariable String id,
            @RequestBody TicketAiConfirmCommand command) {
        Object result = ticketAiAssistantService.confirm(id, command);
        return ApiResponse.ok(result);
    }
}

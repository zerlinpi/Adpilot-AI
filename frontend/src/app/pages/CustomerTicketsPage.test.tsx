// Unit tests for the customer-ticket UI AI-assist proposal flow.
// The Ticket_AI_Assistant surfaces a draft reply, a suggested classification,
// and a suggested handling action as PROPOSALS that require explicit operator
// confirmation before any reply is sent or status change is applied.
// Validates: platform-workspace-rbac Requirements 9.3, 9.4

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { CustomerTicketsPage } from './CustomerTicketsPage';

vi.mock('../lib/api', () => ({
  fetchCustomerTickets: vi.fn(),
  assistCustomerTicket: vi.fn(),
  confirmCustomerTicketProposal: vi.fn(),
}));

vi.mock('../lib/useStoreId', () => ({
  useStoreId: vi.fn(),
}));

import {
  fetchCustomerTickets,
  assistCustomerTicket,
  confirmCustomerTicketProposal,
} from '../lib/api';
import { useStoreId } from '../lib/useStoreId';

const TICKET = {
  id: 'ticket-1',
  ticketNo: 'CS-ABCD1234',
  orderNo: 'ORD-9',
  buyerEmail: 'buyer@example.com',
  subject: '商品损坏',
  description: '收到的商品有划痕',
  category: 'product_quality',
  priority: 'high',
  status: 'pending',
};

const PROPOSAL = {
  ticketId: 'ticket-1',
  draft: '您好，非常抱歉给您带来不便……',
  classification: 'product_quality',
  suggestedAction: 'in_progress',
  generatedBy: 'ai',
};

async function openDrawerAndAssist() {
  render(<CustomerTicketsPage />);
  // Open the detail drawer for the seeded ticket.
  await userEvent.click(await screen.findByText('查看'));
  // Request AI assistance — this only produces proposals.
  await userEvent.click(screen.getByRole('button', { name: /生成 AI 建议/ }));
}

describe('CustomerTicketsPage AI-assist proposals (Req 9.3, 9.4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useStoreId).mockReturnValue({ storeId: 'store-1', loading: false, error: null } as any);
    vi.mocked(fetchCustomerTickets).mockResolvedValue([TICKET]);
    vi.mocked(assistCustomerTicket).mockResolvedValue(PROPOSAL);
    vi.mocked(confirmCustomerTicketProposal).mockResolvedValue({});
  });

  it('presents draft, classification, and action as proposals without sending or applying (Req 9.3, 9.4)', async () => {
    await openDrawerAndAssist();

    // The proposals are surfaced for review.
    expect(await screen.findByText(/建议回复（可编辑）/)).toBeInTheDocument();
    expect(screen.getByText(/以下内容由 AI 生成，仅为建议/)).toBeInTheDocument();
    expect(screen.getByText('建议分类：')).toBeInTheDocument();
    expect(screen.getByText('建议操作：')).toBeInTheDocument();

    // Crucially, requesting assistance must NOT send/apply anything.
    expect(confirmCustomerTicketProposal).not.toHaveBeenCalled();
  });

  it('sends the reply only after explicit confirmation (Req 9.4, 9.5)', async () => {
    await openDrawerAndAssist();
    await screen.findByText(/建议回复（可编辑）/);

    // Still nothing applied until the operator clicks confirm.
    expect(confirmCustomerTicketProposal).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: /确认并发送回复/ }));

    await waitFor(() => expect(confirmCustomerTicketProposal).toHaveBeenCalledTimes(1));
    expect(confirmCustomerTicketProposal).toHaveBeenCalledWith('ticket-1', {
      reply: PROPOSAL.draft,
      classification: 'product_quality',
      action: 'in_progress',
    });
  });

  it('applies classification/action without sending a reply when chosen explicitly (Req 9.4)', async () => {
    await openDrawerAndAssist();
    await screen.findByText(/建议回复（可编辑）/);

    await userEvent.click(screen.getByRole('button', { name: /仅应用分类与操作/ }));

    await waitFor(() => expect(confirmCustomerTicketProposal).toHaveBeenCalledTimes(1));
    expect(confirmCustomerTicketProposal).toHaveBeenCalledWith('ticket-1', {
      classification: 'product_quality',
      action: 'in_progress',
    });
  });

  it('surfaces a failure reason and leaves the ticket unchanged when generation fails (Req 9.6)', async () => {
    vi.mocked(assistCustomerTicket).mockRejectedValue(new Error('AI 服务暂时不可用'));
    await openDrawerAndAssist();

    expect(await screen.findByText('AI 服务暂时不可用')).toBeInTheDocument();
    expect(confirmCustomerTicketProposal).not.toHaveBeenCalled();
  });
});

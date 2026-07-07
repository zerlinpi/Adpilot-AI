package com.adpilot.modules.customer.service.impl;

import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.customer.entity.BuyerMessageEntity;
import com.adpilot.modules.customer.entity.CustomerTicketEntity;
import com.adpilot.modules.customer.mapper.BuyerMessageMapper;
import com.adpilot.modules.customer.mapper.CustomerTicketMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for Store_Group inheritance when a buyer message is
 * converted into a {@link CustomerTicketEntity} by
 * {@link CustomerServiceImpl#convertMessageToTicket(String)}.
 *
 * <p>Feature: platform-workspace-rbac, Property 18: Customer ticket inherits its
 * store's group.
 *
 * <p>Validates: Requirements 9.2.
 *
 * <p>For any buyer message converted into a Customer_Ticket, the ticket's
 * {@code store_group_id} equals the originating Store's {@code store_group_id}.
 * The conversion resolves the message's originating Store and copies that Store's
 * Store_Group onto the new ticket, so the shared data-scope layer can keep the
 * ticket within the operator's Store_Group_Scope (Req 9.7). The Store_Group is
 * inherited verbatim — including the {@code null} (unresolved) case and any group
 * id — never substituted or dropped.
 */
class CustomerTicketStoreGroupInheritancePropertyTest {

    /**
     * Feature: platform-workspace-rbac, Property 18: Customer ticket inherits its
     * store's group.
     *
     * <p>Validates: Requirements 9.2.
     *
     * <p>The ticket persisted by the conversion carries exactly the originating
     * Store's {@code store_group_id} (and the same {@code store_id}), for any
     * store group — present or {@code null}.
     */
    @Property(tries = 100)
    void convertedTicketInheritsOriginatingStoresGroup(
            @ForAll("storeGroupIds") UUID storeGroupId,
            @ForAll("subjects") String subject) {

        BuyerMessageMapper buyerMessageMapper = Mockito.mock(BuyerMessageMapper.class);
        CustomerTicketMapper customerTicketMapper = Mockito.mock(CustomerTicketMapper.class);
        AiAssistService aiAssistService = Mockito.mock(AiAssistService.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        com.adpilot.common.security.DataScopeService dataScopeService =
                Mockito.mock(com.adpilot.common.security.DataScopeService.class);
        CustomerServiceImpl service = new CustomerServiceImpl(
                buyerMessageMapper, customerTicketMapper, aiAssistService, storeMapper, dataScopeService);

        UUID messageId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        // The originating store carries the store group the ticket must inherit.
        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .orgId(UUID.randomUUID())
                .name("store")
                .marketplaceId(UUID.randomUUID())
                .storeGroupId(storeGroupId)
                .build();

        BuyerMessageEntity message = BuyerMessageEntity.builder()
                .id(messageId)
                .storeId(storeId)
                .subject(subject)
                .message("buyer text")
                .build();

        when(buyerMessageMapper.selectById(messageId)).thenReturn(message);
        when(storeMapper.selectById(storeId)).thenReturn(store);

        service.convertMessageToTicket(messageId.toString());

        ArgumentCaptor<CustomerTicketEntity> captor = ArgumentCaptor.forClass(CustomerTicketEntity.class);
        verify(customerTicketMapper).insert(captor.capture());
        CustomerTicketEntity persisted = captor.getValue();

        // The ticket inherits the originating store's group verbatim (Req 9.2).
        assertThat(persisted.getStoreGroupId()).isEqualTo(storeGroupId);
        // ...and stays associated with the originating store.
        assertThat(persisted.getStoreId()).isEqualTo(storeId);
    }

    // --- generators --------------------------------------------------------

    /**
     * Store group ids the originating store may resolve to, including the
     * {@code null} (unresolved) case so inheritance of "no group" is also covered.
     */
    @Provide
    Arbitrary<UUID> storeGroupIds() {
        Arbitrary<UUID> ids = Arbitraries.randomValue(r -> UUID.randomUUID());
        return Arbitraries.oneOf(ids, Arbitraries.just(null));
    }

    /** Buyer-message subjects, including blank, up to the VARCHAR(500) bound. */
    @Provide
    Arbitrary<String> subjects() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .ofMinLength(0)
                .ofMaxLength(120);
    }
}

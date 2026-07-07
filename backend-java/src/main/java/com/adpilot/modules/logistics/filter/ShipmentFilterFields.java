package com.adpilot.modules.logistics.filter;

import com.adpilot.modules.tableview.filter.FilterFieldSpec;
import com.adpilot.modules.tableview.filter.FilterFieldType;

import java.util.Map;

/**
 * The advanced-filter field registry for the {@code shipments} resource (the
 * representative resource for the server-side query / export / select-all
 * endpoints). Maps each client-facing logical field to its physical column and
 * {@link FilterFieldType} so the shared {@code FilterTranslator} can validate and
 * translate conditions (Req 2.7, 2.9, 2.10).
 */
public final class ShipmentFilterFields {

    private ShipmentFilterFields() {
    }

    public static final Map<String, FilterFieldSpec> REGISTRY = FilterFieldSpec.registry(
            FilterFieldSpec.of("shipmentId", "shipment_id", FilterFieldType.TEXT),
            FilterFieldSpec.of("shipmentType", "shipment_type", FilterFieldType.ENUM),
            FilterFieldSpec.of("status", "status", FilterFieldType.ENUM),
            FilterFieldSpec.of("carrier", "carrier", FilterFieldType.TEXT),
            FilterFieldSpec.of("trackingNumber", "tracking_number", FilterFieldType.TEXT),
            FilterFieldSpec.of("fbaShipmentId", "fba_shipment_id", FilterFieldType.TEXT),
            FilterFieldSpec.of("amazonShipmentStatus", "amazon_shipment_status", FilterFieldType.ENUM),
            FilterFieldSpec.of("destinationFcCode", "destination_fc_code", FilterFieldType.TEXT),
            FilterFieldSpec.of("currency", "currency", FilterFieldType.ENUM),
            FilterFieldSpec.of("reportingCurrency", "reporting_currency", FilterFieldType.ENUM),
            FilterFieldSpec.of("totalItems", "total_items", FilterFieldType.NUMBER),
            FilterFieldSpec.of("shippingCost", "shipping_cost", FilterFieldType.NUMBER),
            FilterFieldSpec.of("totalWeight", "total_weight", FilterFieldType.NUMBER),
            FilterFieldSpec.of("shipDate", "ship_date", FilterFieldType.DATE),
            FilterFieldSpec.of("estimatedDeliveryDate", "estimated_delivery_date", FilterFieldType.DATE),
            FilterFieldSpec.of("actualDeliveryDate", "actual_delivery_date", FilterFieldType.DATE)
    );
}

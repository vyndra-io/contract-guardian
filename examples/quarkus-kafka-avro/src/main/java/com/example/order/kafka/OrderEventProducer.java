package com.example.order.kafka;

import com.example.order.avro.OrderItem;
import com.example.order.avro.OrderStatus;
import com.example.order.avro.OrderStatusChange;
import com.example.order.avro.OrderStatusChangedValue;
import com.example.order.avro.OrderValue;
import com.example.order.model.Order;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Produces order events to Kafka topics using Avro serialization.
 *
 * Topics:
 *   - orders: Full order payload on create/update
 *   - order-status-changes: Lightweight event on status transitions
 */
@ApplicationScoped
public class OrderEventProducer {

    private static final Logger LOG = Logger.getLogger(OrderEventProducer.class);

    @Inject
    @Channel("orders-out")
    Emitter<Record<String, OrderValue>> orderEmitter;

    @Inject
    @Channel("order-status-changes-out")
    Emitter<Record<String, OrderStatusChangedValue>> statusChangeEmitter;

    public void sendOrderCreated(Order order) {
        OrderValue event = buildOrderValue(order);
        orderEmitter.send(Record.of(order.getOrderId(), event));
        LOG.infof("Published order-created event for order %s", order.getOrderId());
    }

    public void sendOrderUpdated(Order order) {
        OrderValue event = buildOrderValue(order);
        orderEmitter.send(Record.of(order.getOrderId(), event));
        LOG.infof("Published order-updated event for order %s", order.getOrderId());
    }

    public void sendStatusChanged(String orderId, String previousStatus, String newStatus, String reason) {
        OrderStatusChangedValue event = OrderStatusChangedValue.newBuilder()
                .setOrderId(orderId)
                .setPreviousStatus(OrderStatusChange.valueOf(previousStatus))
                .setNewStatus(OrderStatusChange.valueOf(newStatus))
                .setReason(reason)
                .setChangedAt(Instant.now())
                .build();

        statusChangeEmitter.send(Record.of(orderId, event));
        LOG.infof("Published status-changed event for order %s: %s -> %s", orderId, previousStatus, newStatus);
    }

    private OrderValue buildOrderValue(Order order) {
        return OrderValue.newBuilder()
                .setOrderId(order.getOrderId())
                .setCustomerId(order.getCustomerId())
                .setCustomerEmail(order.getCustomerEmail())
                .setItems(order.getItems().stream()
                        .map(item -> OrderItem.newBuilder()
                                .setProductId(item.getProductId())
                                .setProductName(item.getProductName())
                                .setQuantity(item.getQuantity())
                                .setUnitPrice(item.getUnitPrice())
                                .build())
                        .toList())
                .setTotalAmount(order.getTotalAmount())
                .setStatus(OrderStatus.valueOf(order.getStatus()))
                .setCreatedAt(order.getCreatedAt())
                .setUpdatedAt(order.getUpdatedAt())
                .build();
    }
}

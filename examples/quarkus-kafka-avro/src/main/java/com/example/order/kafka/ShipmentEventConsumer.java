package com.example.order.kafka;

import com.example.order.avro.ShipmentValue;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Consumes shipment events from the logistics service.
 * Updates order status when a shipment is delivered.
 *
 * This consumer depends on the shipment-value.avsc schema owned by the
 * logistics team. Any breaking change to that schema will break this consumer.
 * Contract Guardian catches these issues in CI before they hit production.
 */
@ApplicationScoped
public class ShipmentEventConsumer {

    private static final Logger LOG = Logger.getLogger(ShipmentEventConsumer.class);

    @Inject
    OrderEventProducer orderEventProducer;

    @Incoming("shipments-in")
    public void onShipmentUpdate(Record<String, ShipmentValue> record) {
        ShipmentValue shipment = record.value();
        LOG.infof("Received shipment update: shipment=%s order=%s status=%s",
                shipment.getShipmentId(),
                shipment.getOrderId(),
                shipment.getStatus());

        switch (shipment.getStatus()) {
            case PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY -> {
                LOG.infof("Order %s is in transit via %s (tracking: %s)",
                        shipment.getOrderId(),
                        shipment.getCarrier(),
                        shipment.getTrackingNumber());
            }
            case DELIVERED -> {
                LOG.infof("Order %s has been delivered", shipment.getOrderId());
                orderEventProducer.sendStatusChanged(
                        shipment.getOrderId(),
                        "SHIPPED",
                        "DELIVERED",
                        "Shipment " + shipment.getShipmentId() + " delivered");
            }
            case FAILED -> {
                LOG.warnf("Shipment %s for order %s has failed",
                        shipment.getShipmentId(), shipment.getOrderId());
            }
            default -> LOG.debugf("Ignoring shipment status: %s", shipment.getStatus());
        }
    }
}

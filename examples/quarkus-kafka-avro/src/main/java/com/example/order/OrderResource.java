package com.example.order;

import com.example.order.kafka.OrderEventProducer;
import com.example.order.model.CreateOrderRequest;
import com.example.order.model.Order;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API for order management.
 * Each mutation publishes an event to Kafka using Avro-serialized schemas.
 */
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @Inject
    OrderEventProducer eventProducer;

    @POST
    public Response createOrder(CreateOrderRequest request) {
        Order order = new Order();
        order.setCustomerId(request.getCustomerId());
        order.setCustomerEmail(request.getCustomerEmail());
        order.setItems(request.getItems());
        order.setTotalAmount(request.getItems().stream()
                .mapToDouble(item -> item.getQuantity() * item.getUnitPrice())
                .sum());

        orders.put(order.getOrderId(), order);
        eventProducer.sendOrderCreated(order);

        return Response.status(Response.Status.CREATED).entity(order).build();
    }

    @GET
    @Path("/{orderId}")
    public Response getOrder(@PathParam("orderId") String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(order).build();
    }

    @PUT
    @Path("/{orderId}/cancel")
    public Response cancelOrder(@PathParam("orderId") String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String previousStatus = order.getStatus();
        order.setStatus("CANCELLED");
        order.setUpdatedAt(Instant.now());

        eventProducer.sendStatusChanged(orderId, previousStatus, "CANCELLED", "Cancelled by customer");
        eventProducer.sendOrderUpdated(order);

        return Response.ok(order).build();
    }

    @PUT
    @Path("/{orderId}/confirm")
    public Response confirmOrder(@PathParam("orderId") String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String previousStatus = order.getStatus();
        order.setStatus("CONFIRMED");
        order.setUpdatedAt(Instant.now());

        eventProducer.sendStatusChanged(orderId, previousStatus, "CONFIRMED", null);
        eventProducer.sendOrderUpdated(order);

        return Response.ok(order).build();
    }
}

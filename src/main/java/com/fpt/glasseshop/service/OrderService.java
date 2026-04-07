package com.fpt.glasseshop.service;

import com.fpt.glasseshop.entity.*;
import com.fpt.glasseshop.entity.dto.*;
import com.fpt.glasseshop.exception.ResourceNotFoundException;
import com.fpt.glasseshop.repository.AddressRepository;
import com.fpt.glasseshop.repository.CartRepository;
import com.fpt.glasseshop.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemService orderItemService;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final CartService cartService;
    private final com.fpt.glasseshop.repository.ProductVariantRepository productVariantRepository;

    public Order saveOrder(Order order) {
        Order savedOrder = orderRepository.save(order);
        if (order.getOrderItems() != null) {
            order.getOrderItems().forEach(item -> {
                item.setOrder(savedOrder);
                orderItemService.saveOrderItem(item);
            });
        }
        return savedOrder;
    }

    public List<Order> getOrdersByUserId(Long userId) {
        return orderRepository.findByUserUserId(userId);
    }

    public List<OrderDTO> getOrdersDTOByUserId(Long userId) {
        return getOrdersByUserId(userId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<OrderDTO> getAllOrdersDTO() {
        return orderRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public Optional<Order> getOrderById(Long orderId) {
        return orderRepository.findById(orderId);
    }

    public Optional<OrderDTO> getOrderDTOById(Long orderId) {
        return getOrderById(orderId).map(this::convertToDTO);
    }

    @Transactional
    public OrderDTO updateOrderStatus(Long orderId, String newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
        java.util.List<String> validStatuses = java.util.Arrays.asList("PENDING", "PROCESSING", "DELIVERING", "DELIVERED", "CANCELED");
        if (!validStatuses.contains(newStatus)) {
            throw new IllegalArgumentException("Invalid order status: " + newStatus);
        }

        order.setStatus(newStatus);

        // Check if canceled to restore stock
        if ("CANCELED".equals(newStatus)) {
            for (OrderItem item : order.getOrderItems()) {
                if (item.getVariantId() != null && item.getQuantity() != null) {
                    productVariantRepository.decreaseStock(item.getVariantId(), -item.getQuantity()); // negative decrease = increase
                }
            }
        }
        if ("DELIVERED".equals(newStatus) && order.getDeliveredAt() == null) {
            order.setDeliveredAt(LocalDateTime.now());
        }

        return convertToDTO(orderRepository.save(order));
    }

    @Transactional
    public OrderDTO updatePaymentOrderStatus(Long orderId, String newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
        java.util.List<String> validStatuses = java.util.Arrays.asList("UNPAID", "PAID");
        if (!validStatuses.contains(newStatus)) {
            throw new IllegalArgumentException("Invalid payment status: " + newStatus);
        }

        order.setPaymentStatus(newStatus);

        // Check if canceled to restore stock
        if ("CANCELED".equals(newStatus)) {
            for (OrderItem item : order.getOrderItems()) {
                if (item.getVariantId() != null && item.getQuantity() != null) {
                    productVariantRepository.decreaseStock(item.getVariantId(), -item.getQuantity()); // negative decrease = increase
                }
            }
        }

        return convertToDTO(orderRepository.save(order));
    }

    public void deleteOrder(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new RuntimeException("Order not found with id: " + id);
        }
        orderRepository.deleteById(id);
    }

    @Transactional
    public OrderDTO createOrderFromCart(UserAccount user, CreateOrderRequest request) {
        // 0. Idempotency Check
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().trim().isEmpty()) {
            Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existingOrder.isPresent()) {
                return convertToDTO(existingOrder.get());
            }
        }

        // 1. Get User's Cart with Pessimistic Lock for Concurrency
        Cart cart = cartRepository.findByUserUserIdForCheckout(user.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user: " + user.getUserId()));

        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new IllegalArgumentException("Cannot create order from an empty cart");
        }

        // 2. Initialize Calculations
        BigDecimal totalPrice = BigDecimal.ZERO;
        BigDecimal shippingFee = request.getShippingFee() != null ? request.getShippingFee() : BigDecimal.ZERO;
        BigDecimal voucherDiscount = request.getVoucherDiscount() != null ? request.getVoucherDiscount() : BigDecimal.ZERO;

        // 3. Create Order Object
        String orderCode = "ORD-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Order order = Order.builder()
                .user(user)
                .orderCode(orderCode)
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .address(request.getAddress())
                .note(request.getNote())
                .paymentMethod(request.getPaymentMethod())
                .shippingFee(shippingFee)
                .voucherDiscount(voucherDiscount)
                .idempotencyKey(request.getIdempotencyKey())
                .status("PENDING")
                .paymentStatus("UNPAID")
                .orderDate(LocalDateTime.now())
                .orderItems(new ArrayList<>())
                .build();

        // 4. Create OrderItems from CartItems and Calculate Total
        for (CartItem cartItem : cart.getItems()) {
            if (cartItem.getQuantity() == null || cartItem.getQuantity() <= 0) {
                throw new IllegalArgumentException("Invalid quantity for cart item");
            }

            // Atomic Stock Validation & Deduction (Skip for Preorders)
            boolean isPreorderItem = Boolean.TRUE.equals(cartItem.getIsPreorder()) || Boolean.TRUE.equals(request.getIsPreorder());

            if (!isPreorderItem) {
                int updatedRows = productVariantRepository.decreaseStock(cartItem.getVariant().getVariantId(), cartItem.getQuantity());
                if (updatedRows == 0) {
                    throw new IllegalArgumentException("Insufficient stock for product: " + (cartItem.getVariant().getProduct() != null ? cartItem.getVariant().getProduct().getName() : "Unknown"));
                }
            }

            BigDecimal unitPrice = cartItem.getPrice() != null ? cartItem.getPrice() : BigDecimal.ZERO;
            BigDecimal lensPrice = (cartItem.getLensOption() != null && cartItem.getLensOption().getPrice() != null)
                    ? cartItem.getLensOption().getPrice()
                    : BigDecimal.ZERO;

            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(cartItem.getQuantity()));
            totalPrice = totalPrice.add(subtotal);

            for (int i = 0; i < cartItem.getQuantity(); i++) {

                OrderItem orderItem = OrderItem.builder()
                        .order(order)
                        .variant(cartItem.getVariant())
                        .variantId(cartItem.getVariant() != null ? cartItem.getVariant().getVariantId() : null)
                        .productId(cartItem.getVariant() != null && cartItem.getVariant().getProduct() != null
                                ? cartItem.getVariant().getProduct().getProductId()
                                : (cartItem.getProductId() != null ? cartItem.getProductId() : null))
                        .productName(cartItem.getVariant() != null && cartItem.getVariant().getProduct() != null
                                ? cartItem.getVariant().getProduct().getName()
                                : (cartItem.getProductName() != null ? cartItem.getProductName() : null))
                        .variantColor(cartItem.getVariant() != null ? cartItem.getVariant().getColor() : null)
                        .variantSize(cartItem.getVariant() != null ? cartItem.getVariant().getFrameSize() : null)
                        .imageUrl(cartItem.getVariant() != null ? cartItem.getVariant().getImageUrl() : null)
                        .lensOption(cartItem.getLensOption())
                        .lensOptionId(cartItem.getLensOption() != null ? cartItem.getLensOption().getLensOptionId() : null)
                        .lensType(cartItem.getLensOption() != null ? cartItem.getLensOption().getType() : null)
                        .lensPrice(lensPrice)
                        .lensCoating(cartItem.getLensOption() != null ? cartItem.getLensOption().getCoating() : null)
                        .quantity(1) //  QUAN TRỌNG
                        .unitPrice(unitPrice)
                        .isPreorder(isPreorderItem)
                        .fulfillmentType(cartItem.getPrescription() != null || cartItem.getIsLens() == Boolean.TRUE
                                ? "PRESCRIPTION"
                                : (isPreorderItem ? "PRE_ORDER" : "IN_STOCK"))
                        .sphLeft(cartItem.getPrescription() != null ? cartItem.getPrescription().getSphLeft() : null)
                        .sphRight(cartItem.getPrescription() != null ? cartItem.getPrescription().getSphRight() : null)
                        .cylLeft(cartItem.getPrescription() != null ? cartItem.getPrescription().getCylLeft() : null)
                        .cylRight(cartItem.getPrescription() != null ? cartItem.getPrescription().getCylRight() : null)
                        .axisLeft(cartItem.getPrescription() != null ? cartItem.getPrescription().getAxisLeft() : null)
                        .axisRight(cartItem.getPrescription() != null ? cartItem.getPrescription().getAxisRight() : null)
                        .addLeft(cartItem.getPrescription() != null ? cartItem.getPrescription().getAddLeft() : null)
                        .addRight(cartItem.getPrescription() != null ? cartItem.getPrescription().getAddRight() : null)
                        .pd(cartItem.getPrescription() != null ? cartItem.getPrescription().getPd() : null)
                        .build();

                if (cartItem.getPrescription() != null) {
                    Prescription cartP = cartItem.getPrescription();
                    Prescription p = Prescription.builder()
                            .orderItem(orderItem)
                            .cartItem(null)
                            .sphLeft(cartP.getSphLeft())
                            .sphRight(cartP.getSphRight())
                            .cylLeft(cartP.getCylLeft())
                            .cylRight(cartP.getCylRight())
                            .axisLeft(cartP.getAxisLeft())
                            .axisRight(cartP.getAxisRight())
                            .pd(cartP.getPd())
                            .doctorName(cartP.getDoctorName())
                            .expirationDate(cartP.getExpirationDate())
                            .status(cartP.getStatus() != null ? cartP.getStatus() : false)
                            .build();
                    orderItem.setPrescription(p);
                }

                order.getOrderItems().add(orderItem);
            }
        }

        order.setTotalPrice(totalPrice);
        order.setFinalPrice(totalPrice.add(shippingFee).subtract(voucherDiscount));

        // 5. Save Order
        Order savedOrder = orderRepository.save(order);

        // 6. Clear Cart
        cartService.clearCart(user);

        return convertToDTO(savedOrder);
    }

    // Removed redundant createOrder method.
    public List<OrderItem> getOrderItems(Long orderId) {
        return orderItemService.getOrderItemsByOrderId(orderId);
    }

    private OrderDTO convertToDTO(Order order) {
        return OrderDTO.builder()
                .orderId(order.getOrderId())
                .orderCode(order.getOrderCode())
                .userId(order.getUser() != null ? order.getUser().getUserId() : null)
                .userName(order.getUser() != null ? order.getUser().getName() : null)
                .userEmail(order.getUser() != null ? order.getUser().getEmail() : null)
                .orderDate(order.getOrderDate())
                .status(order.getStatus())
                .totalPrice(order.getTotalPrice())
                .fullName(order.getFullName())
                .phone(order.getPhone())
                .address(order.getAddress())
                .note(order.getNote())
                .shippingFee(order.getShippingFee())
                .voucherDiscount(order.getVoucherDiscount())
                .finalPrice(order.getFinalPrice())
                .paymentStatus(order.getPaymentStatus())
                .paymentMethod(order.getPaymentMethod())
                .orderItems(order.getOrderItems() != null ? order.getOrderItems().stream()
                        .map(this::mapToItemDTO)
                        .collect(Collectors.toList()) : null)
                .totalItems(order.getOrderItems() != null ? order.getOrderItems().size() : 0)
                .build();
    }

    private AddressDTO mapToAddressDTO(com.fpt.glasseshop.entity.Address address) {
        if (address == null)
            return null;
        return AddressDTO.builder()
                .addressId(address.getAddressId())
                .street(address.getStreet())
                .city(address.getCity())
                .country(address.getCountry())
                .build();
    }

    private OrderItemDTO mapToItemDTO(OrderItem item) {
        if (item == null)
            return null;
        return OrderItemDTO.builder()
                .orderItemId(item.getOrderItemId())
                .variantId(item.getVariantId())
                .productId(item.getProductId())
                .productName(item.getProductName())
                .variantColor(item.getVariantColor())
                .variantSize(item.getVariantSize())
                .imageUrl(item.getImageUrl())
                .lensType(item.getLensType())
                .lensPrice(item.getLensPrice())
                .lensCoating(item.getLensCoating())
                .lensOptionId(item.getLensOptionId())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .subtotal(item.getUnitPrice().multiply(new java.math.BigDecimal(item.getQuantity())))
                .fulfillmentType(item.getFulfillmentType())
                .itemType(item.getItemType())
                .isPreorder(item.getIsPreorder())
                // Populating manual prescription entry data in DTO
                .sphLeft(item.getSphLeft())
                .sphRight(item.getSphRight())
                .cylLeft(item.getCylLeft())
                .cylRight(item.getCylRight())
                .axisLeft(item.getAxisLeft())
                .axisRight(item.getAxisRight())
                .addLeft(item.getAddLeft())
                .addRight(item.getAddRight())
                .pd(item.getPd())
                .prescription(mapToPrescriptionDTO(item.getPrescription()))
                .build();
    }

    private PrescriptionDTO mapToPrescriptionDTO(Prescription p) {
        if (p == null)
            return null;
        return PrescriptionDTO.builder()
                .prescriptionId(p.getPrescriptionId())
                .orderItemId(p.getOrderItem().getOrderItemId())
                .sphLeft(p.getSphLeft())
                .sphRight(p.getSphRight())
                .cylLeft(p.getCylLeft())
                .cylRight(p.getCylRight())
                .axisLeft(p.getAxisLeft())
                .axisRight(p.getAxisRight())
                .pd(p.getPd())
                .doctorName(p.getDoctorName())
                .expirationDate(p.getExpirationDate())
                .status(p.getStatus())
                .adminNote(p.getAdminNote())
                .createdAt(p.getCreatedAt())
                .build();
    }

    public long getTotalCustomersPaid() {
        return orderRepository.countCustomersPaid();
    }

    public long getTotalOrdersPaid() {
        return orderRepository.count();
    }


}

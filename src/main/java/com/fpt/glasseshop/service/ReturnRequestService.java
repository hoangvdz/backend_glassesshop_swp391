package com.fpt.glasseshop.service;

import com.fpt.glasseshop.entity.*;
import com.fpt.glasseshop.entity.dto.ReturnRequestDTO;
import com.fpt.glasseshop.entity.dto.ReturnRequestResponseDTO;

import com.fpt.glasseshop.repository.*;
import lombok.RequiredArgsConstructor;
import org.apache.coyote.BadRequestException;
import org.springframework.security.access.AccessDeniedException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReturnRequestService {

    private final ReturnRequestRepository returnRequestRepo;
    private final OrderRepository orderRepository;
    private final UserAccountRepository userAccountRepository;
    private final OrderItemRepository orderItemRepository;
    private final PrescriptionRepository prescriptionRepo;
    private final NotificationService notificationService;
    private final ProductVariantRepository proVariantRepo;


    private UserAccount getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        String role = SecurityContextHolder.getContext().getAuthentication().getAuthorities().toString();
        System.out.println("EMAIL FROM SECURITY CONTEXT = " + email);
        System.out.println("TOKEN ROLE = " + role);
        if (email == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        return userAccountRepository.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("User not found"));
    }

    public ReturnRequestResponseDTO createReturnRequest(ReturnRequestDTO dto) throws BadRequestException {

        UserAccount currentUser = getCurrentUser();

        OrderItem orderItem = orderItemRepository.findById(dto.getOrderItemId())
                .orElseThrow(() -> new RuntimeException("Order not found"));
        Order order = orderItem.getOrder();

        // 1. check owner
        if (order.getUser() == null || !order.getUser().getUserId().equals(currentUser.getUserId())) {
            throw new BadRequestException("You are not allowed to return this order item");
        }

        // 2. đơn delivered mới được đổi/trả
        if (!"DELIVERED".equalsIgnoreCase(order.getStatus())) {
            throw new BadRequestException("Only delivered orders can be returned");
        }

        // 4. validate return quantity
        if (dto.getReturnQuantity() == null || dto.getReturnQuantity() <= 0) {
            throw new BadRequestException("Return quantity must be greater than 0");
        }

        if (dto.getReturnQuantity() > orderItem.getQuantity()) {
            throw new BadRequestException("Return quantity cannot exceed purchased quantity");
        }

        Integer requestedQty = returnRequestRepo.sumRequestedQuantityByOrderItemId(orderItem.getOrderItemId(),ReturnRequest.ReturnStatus.REJECTED);
        int remainingQty = orderItem.getQuantity() - (requestedQty != null ? requestedQty : 0);

        if (remainingQty <= 0) {
            throw new BadRequestException("All quantities of this item have already been requested for return/exchange");
        }

        if (dto.getReturnQuantity() > remainingQty) {
            throw new BadRequestException("Only " + remainingQty + " item(s) remaining for return/exchange");
        }

        // 5. check time 7 day
        if (order.getDeliveredAt() != null &&
                order.getDeliveredAt().plusDays(7).isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Return period expired");
        }

        // 6. validate reason
        if (dto.getReason() == null || dto.getReason().trim().isEmpty()) {
            throw new BadRequestException("Reason is required");
        }

        ReturnRequest request = ReturnRequest.builder()
                .orderItem(orderItem)
                .returnQuantity(dto.getReturnQuantity())
                .reason(dto.getReason())
                .description(dto.getDescription())
                .imageUrl(dto.getImageUrl())
                .status(ReturnRequest.ReturnStatus.PENDING)
                .requestType(
                        "EXCHANGE".equalsIgnoreCase(dto.getRequestType())
                                ? ReturnRequest.RequestType.EXCHANGE
                                : ReturnRequest.RequestType.RETURN
                )
                .build();

        ReturnRequest saved = returnRequestRepo.save(request);

        notificationService.notifyAdmins(
            "New Return Request", 
            "A new " + saved.getRequestType() + " request has been submitted for item " + orderItem.getProductName(),
            "RETURN",
            saved.getRequestId()
        );

        return mapToDTO(saved);
    }

    public List<ReturnRequestResponseDTO> getAll() {
        return returnRequestRepo.findAll()
                .stream()
                .map(this::mapToDTO)
                .toList();
    }



    public List<ReturnRequestResponseDTO> getByOrderItemId(Long orderItemId) {
        return returnRequestRepo.findAllByOrderItemOrderItemId(orderItemId)
                .stream()
                .map(this::mapToDTO)
                .toList();
    }

    @Transactional
    public ReturnRequest approveRequest(Long requestId) {
        ReturnRequest request = returnRequestRepo.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Return request not found"));

        validatePendingRequest(request);

        ReturnRequest saved = returnRequestRepo.save(request);


        // Notify user
        UserAccount user = request.getOrderItem().getOrder().getUser();
        notificationService.createNotification(
            user, 
            "Return Request Approved", 
            "Your " + request.getRequestType() + " request for " + request.getOrderItem().getProductName() + " has been approved.",
            "RETURN",
            request.getRequestId()
        );

        if (request.getRequestType() == ReturnRequest.RequestType.EXCHANGE) {
            validateAndReserveStockForExchange(request);
            createReplacementOrderForExchange(request);
        }


        return saved;
    }
    private void validateAndReserveStockForExchange(ReturnRequest request) {
        OrderItem orderItem = getRequiredOrderItem(request);

        if (orderItem.getVariantId() == null) {
            throw new RuntimeException("Variant not found for exchange item");
        }

        ProductVariant variant = proVariantRepo.findById(orderItem.getVariantId())
                .orElseThrow(() -> new RuntimeException("Variant not found"));

        Integer requestedQty = request.getReturnQuantity();
        Integer currentStock = variant.getStockQuantity();

        if (currentStock == null || currentStock <= 0) {
            throw new RuntimeException("This product is out of stock, cannot process exchange");
        }

        if (currentStock < requestedQty) {
            throw new RuntimeException(
                    "Not enough stock for exchange. Available: " + currentStock
                            + ", requested: " + requestedQty
            );
        }

        variant.setStockQuantity(currentStock - requestedQty);
        proVariantRepo.save(variant);
    }

    @Transactional
    public ReturnRequest rejectRequest(Long requestId, String rejectionReason) {
        ReturnRequest request = returnRequestRepo.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Return request not found"));

        validatePendingRequest(request);

        if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
            throw new RuntimeException("Rejection reason is required");
        }

        request.setStatus(ReturnRequest.ReturnStatus.REJECTED);
        request.setRejectionReason(rejectionReason.trim());
        request.setResolvedAt(LocalDateTime.now());

        ReturnRequest saved = returnRequestRepo.save(request);

        // Notify user
        UserAccount user = request.getOrderItem().getOrder().getUser();
        notificationService.createNotification(
            user, 
            "Return Request Rejected", 
            "Your " + request.getRequestType() + " request for " + request.getOrderItem().getProductName() + " has been rejected. Reason: " + rejectionReason,
            "RETURN",
            request.getRequestId()
        );

        return saved;
    }

    @Transactional
    public ReturnRequest completeRequest(Long requestId) {
        ReturnRequest request = returnRequestRepo.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Return request not found"));

        if (request.getStatus() != ReturnRequest.ReturnStatus.APPROVED) {
            throw new RuntimeException("Only approved requests can be completed");
        }

        request.setStatus(ReturnRequest.ReturnStatus.COMPLETED);
        request.setResolvedAt(LocalDateTime.now());

        ReturnRequest saved = returnRequestRepo.save(request);

        // Notify user
        UserAccount user = request.getOrderItem().getOrder().getUser();
        notificationService.createNotification(
            user, 
            "Return Process Completed", 
            "The " + request.getRequestType() + " process for " + request.getOrderItem().getProductName() + " is now complete.",
            "RETURN",
            request.getRequestId()
        );

        return saved;
    }

    public ReturnRequestResponseDTO mapToDTO(ReturnRequest request) {
        OrderItem orderItem = request.getOrderItem();
        return ReturnRequestResponseDTO.builder()
                .requestId(request.getRequestId())
                .orderId(
                        request.getOrderItem() != null && request.getOrderItem().getOrder() != null
                                ? request.getOrderItem().getOrder().getOrderId()
                                : null
                )
                .orderItemId(request.getOrderItem() != null ? request.getOrderItem().getOrderItemId() : null)
                .returnQuantity(request.getReturnQuantity())
                .reason(request.getReason())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .status(request.getStatus() != null ? request.getStatus().name() : null)
                .rejectionReason(request.getRejectionReason())
                .requestType(request.getRequestType() != null ? request.getRequestType().name() : null)
                .replacementOrderItemId(request.getReplacementOrderItemId())
                .requestedAt(request.getRequestedAt())
                .resolvedAt(request.getResolvedAt())
                // product info
                .productName(orderItem != null ? orderItem.getProductName() : null)
                .productImageUrl(orderItem != null ? orderItem.getImageUrl() : null)
                .variantColor(orderItem != null ? orderItem.getVariantColor() : null)
                .variantSize(orderItem != null ? orderItem.getVariantSize() : null)
                .purchasedQuantity(orderItem != null ? orderItem.getQuantity() : null)
                .unitPrice(orderItem != null ? orderItem.getUnitPrice() : null)
                .lensType(orderItem != null ? orderItem.getLensType() : null)
                .lensCoating(orderItem != null ? orderItem.getLensCoating() : null)
                .build();
    }

    private void validatePendingRequest(ReturnRequest request) {
        if (request.getStatus() != ReturnRequest.ReturnStatus.PENDING) {
            throw new RuntimeException("Only pending requests can be updated");
        }
    }

    private void createReplacementOrderForExchange(ReturnRequest request) {
        OrderItem oldItem = getRequiredOrderItem(request);
        Integer exchangeQty = request.getReturnQuantity();

        Order newOrder = createReplacementOrder(oldItem, exchangeQty);
        OrderItem savedNewItem = orderItemRepository.save(cloneOrderItem(oldItem, newOrder, exchangeQty));

        clonePrescriptionIfNeeded(oldItem, savedNewItem);

        request.setReplacementOrderId(newOrder.getOrderId());
        request.setReplacementOrderItemId(savedNewItem.getOrderItemId());
    }

    private OrderItem getRequiredOrderItem(ReturnRequest request) {
        OrderItem orderItem = request.getOrderItem();
        if (orderItem == null) {
            throw new RuntimeException("Order item not found");
        }
        if (orderItem.getOrder() == null) {
            throw new RuntimeException("Original order not found");
        }
        return orderItem;
    }

    private Order createReplacementOrder(OrderItem oldItem, Integer quantity) {
        Order oldOrder = oldItem.getOrder();

        BigDecimal totalPrice = oldItem.getUnitPrice()
                .multiply(BigDecimal.valueOf(quantity));

        return orderRepository.save(
                Order.builder()
                        .user(oldOrder.getUser())
                        .status("PENDING")
                        .paymentStatus("PAID")
                        .paymentMethod("EXCHANGE")
                        .shippingAddress(oldOrder.getShippingAddress())
                        .billingAddress(oldOrder.getBillingAddress())
                        .totalPrice(totalPrice)
                        .build()
        );
    }

    private OrderItem cloneOrderItem(OrderItem oldItem, Order newOrder, Integer quantity) {
        return OrderItem.builder()
                .order(newOrder)
                .variant(oldItem.getVariant())
                .lensOption(oldItem.getLensOption())
                .quantity(quantity)
                .unitPrice(oldItem.getUnitPrice())
                .fulfillmentType(oldItem.getFulfillmentType())

                .variantId(oldItem.getVariantId())
                .productId(oldItem.getProductId())
                .productName(oldItem.getProductName())
                .variantColor(oldItem.getVariantColor())
                .variantSize(oldItem.getVariantSize())
                .imageUrl(oldItem.getImageUrl())

                .lensType(oldItem.getLensType())
                .lensPrice(oldItem.getLensPrice())
                .lensCoating(oldItem.getLensCoating())
                .lensOptionId(oldItem.getLensOptionId())

                .sphLeft(oldItem.getSphLeft())
                .sphRight(oldItem.getSphRight())
                .cylLeft(oldItem.getCylLeft())
                .cylRight(oldItem.getCylRight())
                .axisLeft(oldItem.getAxisLeft())
                .axisRight(oldItem.getAxisRight())
                .addLeft(oldItem.getAddLeft())
                .addRight(oldItem.getAddRight())
                .pd(oldItem.getPd())

                .isPreorder(oldItem.getIsPreorder())
                .build();
    }

    private void clonePrescriptionIfNeeded(OrderItem oldItem, OrderItem newItem) {
        Prescription oldPrescription = oldItem.getPrescription();
        if (oldPrescription == null) return;

        prescriptionRepo.save(
                Prescription.builder()
                        .orderItem(newItem)
                        .user(oldPrescription.getUser())
                        .name(oldPrescription.getName())
                        .sphLeft(oldPrescription.getSphLeft())
                        .sphRight(oldPrescription.getSphRight())
                        .cylLeft(oldPrescription.getCylLeft())
                        .cylRight(oldPrescription.getCylRight())
                        .axisLeft(oldPrescription.getAxisLeft())
                        .axisRight(oldPrescription.getAxisRight())
                        .addLeft(oldPrescription.getAddLeft())
                        .addRight(oldPrescription.getAddRight())
                        .pd(oldPrescription.getPd())
                        .doctorName(oldPrescription.getDoctorName())
                        .expirationDate(oldPrescription.getExpirationDate())
                        .status(oldPrescription.getStatus())
                        .adminNote(oldPrescription.getAdminNote())
                        .build()
        );
    }
}

package com.fpt.glasseshop.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "return_request")
public class ReturnRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long requestId;

    @ManyToOne
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;

    @Column(name = "reason", columnDefinition = "NVARCHAR(MAX)")
    private String reason;

    @Enumerated(EnumType.STRING)
    private ReturnStatus status;

    @CreationTimestamp
    private LocalDateTime requestedAt;
    private LocalDateTime resolvedAt;

    public enum ReturnStatus {
        PENDING, APPROVED, REJECTED, COMPLETED
    }

    @Column(name = "description", columnDefinition = "NVARCHAR(MAX)")
    private String description;

    // Ảnh minh chứng
    @Column(columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "rejection_reason", columnDefinition = "NVARCHAR(MAX)")
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type")
    private RequestType requestType;

    @Column(name = "replacement_order_id")
    private Long replacementOrderId;

    @Column(name = "replacement_order_item_id")
    private Long replacementOrderItemId;

    @Column(nullable = false)
    private Integer returnQuantity;

    public enum RequestType {
        RETURN, EXCHANGE
    }

}

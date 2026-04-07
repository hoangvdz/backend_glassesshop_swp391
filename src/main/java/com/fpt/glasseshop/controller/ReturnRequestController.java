package com.fpt.glasseshop.controller;

import com.fpt.glasseshop.entity.Order;
import com.fpt.glasseshop.entity.ReturnRequest;
import com.fpt.glasseshop.entity.UserAccount;
import com.fpt.glasseshop.entity.dto.ApiResponse;
import com.fpt.glasseshop.entity.dto.ReturnRequestDTO;
import com.fpt.glasseshop.entity.dto.ReturnRequestResponseDTO;
import com.fpt.glasseshop.entity.dto.UpdateReturnStatusDTO;
import com.fpt.glasseshop.repository.UserAccountRepository;
import com.fpt.glasseshop.service.ReturnRequestService;
import lombok.RequiredArgsConstructor;
import org.apache.coyote.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api/return-requests")
public class ReturnRequestController {

    private final ReturnRequestService returnRequestService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReturnRequestResponseDTO>> createReturnRequest(
            @RequestBody ReturnRequestDTO dto) throws BadRequestException {

        ReturnRequestResponseDTO saved = returnRequestService.createReturnRequest(dto);
        return ResponseEntity.ok(ApiResponse.success("Return request created successfully", saved));
    }
    @GetMapping
    public ResponseEntity<ApiResponse<List<ReturnRequestResponseDTO>>> getAllReturnRequests() {
        List<ReturnRequestResponseDTO> list = returnRequestService.getAll();
        return ResponseEntity.ok(ApiResponse.success("Return requests fetched successfully", list));
    }
    //ADMIN UPDATE
    @PatchMapping("/{requestId}/status")
    public ResponseEntity<ApiResponse<ReturnRequestResponseDTO>> updateStatus(
            @PathVariable Long requestId,
            @RequestBody UpdateReturnStatusDTO request
    ) {
        ReturnRequest updatedRequest;

        switch (request.getStatus()) {
            case APPROVED:
                updatedRequest = returnRequestService.approveRequest(requestId);
                break;
            case REJECTED:
                updatedRequest = returnRequestService.rejectRequest(requestId, request.getRejectionReason());
                break;
            case COMPLETED:
                updatedRequest = returnRequestService.completeRequest(requestId);
                break;
            default:
                throw new RuntimeException("Invalid status");
        }

        ReturnRequestResponseDTO data = returnRequestService.mapToDTO(updatedRequest);
        return ResponseEntity.ok(ApiResponse.success("Status updated successfully", data));
    }

    @GetMapping("/order-item/{orderItemId}")
    public ResponseEntity<ApiResponse<List<ReturnRequestResponseDTO>>> getByOrderItemId(
            @PathVariable Long orderItemId) {

        List<ReturnRequestResponseDTO> dto = returnRequestService.getByOrderItemId(orderItemId);
        return ResponseEntity.ok(ApiResponse.success("Return request fetched successfully", dto));
    }

}

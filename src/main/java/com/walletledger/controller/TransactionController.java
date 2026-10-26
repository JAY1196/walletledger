package com.walletledger.controller;

import com.walletledger.context.UserContext;
import com.walletledger.service.MySqlDeadlockRetryExecutor;
import com.walletledger.service.TransactionReversalService;
import com.walletledger.service.TransferHttpResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionReversalService reversalService;
    private final MySqlDeadlockRetryExecutor deadlockRetryExecutor;

    public TransactionController(
            TransactionReversalService reversalService,
            MySqlDeadlockRetryExecutor deadlockRetryExecutor) {
        this.reversalService = reversalService;
        this.deadlockRetryExecutor = deadlockRetryExecutor;
    }

    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/{id}/reverse")
    public ResponseEntity<String> reverse(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {

        TransferHttpResponse response = deadlockRetryExecutor.execute(
                () -> reversalService.reverse(
                        UserContext.getRequiredUserId(servletRequest),
                        id,
                        idempotencyKey));

        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
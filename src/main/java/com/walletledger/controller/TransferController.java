package com.walletledger.controller;

import com.walletledger.context.UserContext;
import com.walletledger.dto.TransferRequest;
import com.walletledger.service.TransferHttpResponse;
import com.walletledger.service.MySqlDeadlockRetryExecutor;
import com.walletledger.service.TransferService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;
    private final MySqlDeadlockRetryExecutor deadlockRetryExecutor;

    public TransferController(
            TransferService transferService,
            MySqlDeadlockRetryExecutor deadlockRetryExecutor) {
        this.transferService = transferService;
        this.deadlockRetryExecutor = deadlockRetryExecutor;
    }

    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    public ResponseEntity<String> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {

        TransferHttpResponse response = deadlockRetryExecutor.execute(
                () -> transferService.transfer(
                        UserContext.getRequiredUserId(servletRequest),
                        idempotencyKey,
                        request
                ));

        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}

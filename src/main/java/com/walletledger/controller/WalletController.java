package com.walletledger.controller;

import com.walletledger.dto.MoneyMovementRequest;
import com.walletledger.dto.WalletResponse;
import com.walletledger.dto.WalletStatementResponse;
import com.walletledger.service.WalletStatementService;
import com.walletledger.service.TransferHttpResponse;
import com.walletledger.service.MySqlDeadlockRetryExecutor;
import com.walletledger.service.WalletMoneyMovementService;
import com.walletledger.service.WalletService;
import com.walletledger.context.UserContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;
    private final WalletMoneyMovementService moneyMovementService;
    private final MySqlDeadlockRetryExecutor deadlockRetryExecutor;
    private final WalletStatementService walletStatementService;

    public WalletController(
            WalletService walletService,
            WalletMoneyMovementService moneyMovementService,
            MySqlDeadlockRetryExecutor deadlockRetryExecutor,
            WalletStatementService walletStatementService) {
        this.walletService = walletService;
        this.moneyMovementService = moneyMovementService;
        this.deadlockRetryExecutor = deadlockRetryExecutor;
        this.walletStatementService = walletStatementService;
    }

    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(
            HttpServletRequest request) {

        WalletResponse response = walletService.createWallet(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/{id}/balance")
    public ResponseEntity<WalletResponse> getBalance(
            @PathVariable UUID id,
            HttpServletRequest request) {

        WalletResponse response = walletService.getBalance(id, request);

        return ResponseEntity.ok(response);
    }

    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/{id}/topup")
    public ResponseEntity<String> topUp(
            @PathVariable UUID id,
            @Valid @RequestBody MoneyMovementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {

        TransferHttpResponse response = deadlockRetryExecutor.execute(
                () -> moneyMovementService.topUp(
                        UserContext.getRequiredUserId(servletRequest),
                        id,
                        idempotencyKey,
                        request
                ));

        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<String> withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody MoneyMovementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {

        TransferHttpResponse response = deadlockRetryExecutor.execute(
                () -> moneyMovementService.withdraw(
                        UserContext.getRequiredUserId(servletRequest),
                        id,
                        idempotencyKey,
                        request
                ));

        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/{id}/transactions")
    public ResponseEntity<WalletStatementResponse> getStatement(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {

        WalletStatementResponse response =
                walletStatementService.getStatement(
                        id, limit, cursor, request);

        return ResponseEntity.ok(response);
    }}
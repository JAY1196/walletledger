package com.walletledger;

import com.walletledger.entity.User;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.reconciliation.ReconciliationViolationCounts;
import com.walletledger.reconciliation.SqlReconciliationChecker;
import com.walletledger.repository.UserRepository;
import com.walletledger.repository.WalletRepository;
import com.walletledger.service.LedgerInvariantChecker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReconciliationCheckerParityTest {

    @Autowired
    private LedgerInvariantChecker ledgerInvariantChecker;

    @Autowired
    private SqlReconciliationChecker sqlReconciliationChecker;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    @Transactional
    void bothCheckersAgreeOnWalletAndGlobalBalanceViolations()
            throws Exception {

        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userId,
                "Reconciliation Parity User",
                "parity-" + suffix + "@walletledger.test"
        ));

        UUID walletId = UUID.randomUUID();

        Wallet wallet = new Wallet(
                walletId,
                userId,
                WalletType.USER
        );

        wallet.credit(100L);
        walletRepository.saveAndFlush(wallet);

        long jpaWalletViolations =
                ledgerInvariantChecker.findWalletBalanceViolations().size();

        long jpaGlobalViolations =
                ledgerInvariantChecker.findGlobalBalanceViolations().size();

        Connection connection =
                DataSourceUtils.getConnection(dataSource);

        ReconciliationViolationCounts sqlCounts;

        try {
            sqlCounts = sqlReconciliationChecker.check(connection);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }

        assertEquals(1L, jpaWalletViolations,
                "JPA checker should detect exactly one wallet mismatch");

        assertEquals(1L, sqlCounts.walletBalanceViolations(),
                "SQL checker should detect exactly one wallet mismatch");

        assertEquals(jpaWalletViolations,
                sqlCounts.walletBalanceViolations(),
                "JPA and SQL wallet-balance checks must agree");

        assertEquals(1L, jpaGlobalViolations,
                "JPA checker should detect exactly one global imbalance");

        assertEquals(1L, sqlCounts.globalBalanceViolations(),
                "SQL checker should detect exactly one global imbalance");

        assertEquals(jpaGlobalViolations,
                sqlCounts.globalBalanceViolations(),
                "JPA and SQL global-balance checks must agree");
    }
}

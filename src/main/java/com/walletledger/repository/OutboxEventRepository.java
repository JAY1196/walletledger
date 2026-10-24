package com.walletledger.repository;

import com.walletledger.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByPublishedFalseOrderByIdAsc();
}
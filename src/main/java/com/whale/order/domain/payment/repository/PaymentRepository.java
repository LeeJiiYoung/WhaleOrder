package com.whale.order.domain.payment.repository;

import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrders(Orders orders);
}

package com.whale.order.domain.store.entity;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long storeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Member owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 10)
    private String postalCode;

    @Column(nullable = false)
    private String address;

    private String addressDetail;

    private String phone;

    @Column(nullable = false)
    private LocalTime openTime;

    @Column(nullable = false)
    private LocalTime closeTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoreStatus status;

    @Builder
    public Store(Member owner, String name, String postalCode, String address,
                 String addressDetail, String phone, LocalTime openTime, LocalTime closeTime) {
        this.owner = owner;
        this.name = name;
        this.postalCode = postalCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.phone = phone;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.status = StoreStatus.CLOSED;
    }

    public void updateInfo(String name, String postalCode, String address,
                           String addressDetail, String phone,
                           LocalTime openTime, LocalTime closeTime) {
        this.name = name;
        this.postalCode = postalCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.phone = phone;
        this.openTime = openTime;
        this.closeTime = closeTime;
    }

    public void open()  { this.status = StoreStatus.OPEN; }
    public void close() { this.status = StoreStatus.CLOSED; }
}

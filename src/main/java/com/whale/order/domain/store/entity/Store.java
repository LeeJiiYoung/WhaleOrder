package com.whale.order.domain.store.entity;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 매장 Entity.
 * 점주(OWNER)가 소유하며, 영업시간과 위치 정보를 관리한다.
 * 위도/경도는 지도 API 기반 매장 검색에 활용된다.
 */
@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long storeId;

    // 매장 점주 - OWNER 권한을 가진 회원
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Member owner;

    @Column(nullable = false, length = 100)
    private String name;

    // 우편번호 (5자리)
    @Column(nullable = false, length = 10)
    private String postalCode;

    // 도로명/지번 기본주소
    @Column(nullable = false)
    private String address;

    // 상세주소 (동/호수 등) - 없을 수 있음
    private String addressDetail;

    // 지도 API 기반 매장 검색에 사용 (소수점 7자리 = 약 1cm 정밀도)
    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

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
                 String addressDetail, BigDecimal latitude, BigDecimal longitude,
                 String phone, LocalTime openTime, LocalTime closeTime) {
        this.owner = owner;
        this.name = name;
        this.postalCode = postalCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.latitude = latitude;
        this.longitude = longitude;
        this.phone = phone;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.status = StoreStatus.CLOSED; // 등록 시 기본값은 마감 상태
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

    public void open() {
        this.status = StoreStatus.OPEN;
    }

    public void close() {
        this.status = StoreStatus.CLOSED;
    }
}
